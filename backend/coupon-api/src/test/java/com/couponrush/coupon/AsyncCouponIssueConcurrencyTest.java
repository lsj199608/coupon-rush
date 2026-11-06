package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.ConcurrencyTestSupport.Result;
import com.couponrush.coupon.issue.async.AsyncCouponIssueRequester;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.IssueRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 비동기 발급의 동시성: 접수(202)는 재고만큼만 되고, 컨슈머 처리 후 DB 도 정확히 재고만큼이어야 한다.
 * 접수 완료 시간(사용자가 응답을 받는 시간)과 DB 반영 완료 시간을 따로 잰다.
 */
@IntegrationTest
class AsyncCouponIssueConcurrencyTest {

    private static final int THREAD_COUNT = 32;

    @Autowired AsyncCouponIssueRequester requester;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        cleanUp(jdbcTemplate, redisTemplate);
    }

    @ParameterizedTest(name = "재고 {0} / 요청 {1}")
    @CsvSource({"100, 1000", "1000, 1000"})
    void 동시에_요청해도_재고만큼만_접수되고_DB_에도_정확히_재고만큼_저장된다(int stock, int users) throws Exception {
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, stock);
        List<Long> userIds = createUsers(jdbcTemplate, users);

        long startedAt = System.nanoTime();
        Result accepted = runConcurrently(userIds.stream()
                .map(userId -> (Runnable) () -> requester.request(coupon.getId(), userId))
                .toList(), THREAD_COUNT);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> issueRequestRepository.countByCouponIdAndStatus(coupon.getId(), IssueRequestStatus.COMPLETED) == stock);
        long completedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        long dbRows = couponIssueRepository.countByCouponId(coupon.getId());
        int dbCounter = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        Integer duplicated = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (SELECT user_id FROM coupon_issues WHERE coupon_id = ? GROUP BY user_id HAVING count(*) > 1) d
                """, Integer.class, coupon.getId());

        System.out.printf("""
                ========== [비동기 동시성] 재고 %d / 요청 %d / 스레드 %d ==========
                  접수 응답: %s
                  접수 완료(모든 응답 반환): %d ms / DB 반영 완료: %d ms
                  DB 발급 %d / DB 카운터 %d / Redis 남은 재고 %d / Redis Set %d
                ==========================================================%n""",
                stock, users, THREAD_COUNT, accepted.outcomes(), accepted.elapsed().toMillis(), completedMillis,
                dbRows, dbCounter, redisStock.getStock(coupon.getId()).orElseThrow(), redisStock.getIssuedCount(coupon.getId()));

        assertThat(accepted.count("SUCCESS")).as("접수(202) = 재고").isEqualTo(stock);
        assertThat(accepted.count("COUPON_SOLD_OUT")).as("나머지는 소진").isEqualTo(users - stock);
        assertThat(dbRows).as("DB 발급 = 재고 (초과 발급 0)").isEqualTo(stock);
        assertThat(duplicated).as("중복 발급 0").isZero();
        assertThat(dbCounter).as("DB 카운터 = 발급 행").isEqualTo(stock);
        assertThat(issueRequestRepository.countByCouponIdAndStatus(coupon.getId(), IssueRequestStatus.FAILED)).isZero();
        assertThat(redisStock.getStock(coupon.getId())).as("Redis 남은 재고 0").contains(0);
        assertThat(redisStock.getIssuedCount(coupon.getId())).as("Redis Set = DB 발급").isEqualTo(stock);
    }
}

package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.ConcurrencyTestSupport.Result;
import com.couponrush.coupon.issue.redis.RedisCouponIssuer;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 차감은 성공했지만 DB 저장이 실패하는 요청이 동시에 대량으로 섞여도,
 * 보상(Lua) 후 Redis 와 DB 가 정확히 맞는지 검증한다.
 * 실패는 존재하지 않는 userId 로 강제한다 (coupon_issues.user_id FK 위반 → INSERT 실패 → 롤백 → 보상).
 */
@IntegrationTest
class RedisCompensationConcurrencyTest {

    private static final int STOCK = 100;
    private static final int VALID_USERS = 300;
    private static final int FAILING_REQUESTS = 700;
    private static final int THREAD_COUNT = 32;

    @Autowired RedisCouponIssuer issuer;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        cleanUp(jdbcTemplate, redisTemplate);
    }

    @Test
    void DB_저장_실패가_대량으로_섞여도_보상_후_Redis_와_DB_가_정확히_맞는다() throws Exception {
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, STOCK);
        List<Long> validUserIds = createUsers(jdbcTemplate, VALID_USERS);
        long maxId = validUserIds.stream().mapToLong(Long::longValue).max().orElse(0);

        List<Runnable> tasks = new ArrayList<>();
        validUserIds.forEach(userId -> tasks.add(() -> issuer.issue(coupon.getId(), userId)));
        LongStream.rangeClosed(1, FAILING_REQUESTS)
                .forEach(i -> tasks.add(() -> issuer.issue(coupon.getId(), maxId + 1_000_000 + i)));   // 없는 회원
        Collections.shuffle(tasks, new Random(7));

        Result result = runConcurrently(tasks, THREAD_COUNT);

        long dbRows = couponIssueRepository.countByCouponId(coupon.getId());
        int redisRemaining = redisStock.getStock(coupon.getId()).orElseThrow();
        long redisSetSize = redisStock.getIssuedCount(coupon.getId());
        int dbCounter = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        Integer invalidUsersInDb = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coupon_issues WHERE coupon_id = ? AND user_id > ?", Integer.class, coupon.getId(), maxId);

        System.out.printf("""
                ========== [보상 동시성] 정상 %d명 + 실패 강제 %d건 / 재고 %d ==========
                  응답: %s
                  DB 발급 행 %d / DB 카운터 %d / Redis 남은 재고 %d / Redis Set %d
                ==========================================================%n""",
                VALID_USERS, FAILING_REQUESTS, STOCK, result.outcomes(),
                dbRows, dbCounter, redisRemaining, redisSetSize);

        // 불변식: 보상이 몰려도 두 저장소가 서로 정확히 맞아야 한다
        assertThat(redisRemaining + dbRows).as("Redis 남은 재고 + DB 발급 = 총 수량").isEqualTo(STOCK);
        assertThat(redisSetSize).as("Redis Set = DB 발급 행 (실패한 userId 는 모두 제거)").isEqualTo(dbRows);
        assertThat(dbCounter).as("DB 카운터 = DB 발급 행").isEqualTo((int) dbRows);
        assertThat(invalidUsersInDb).as("실패한 요청은 DB 에 남지 않는다").isZero();
        assertThat(dbRows).as("초과 발급 없음").isLessThanOrEqualTo(STOCK);
        assertThat(result.count("SUCCESS")).as("성공 응답 = DB 발급 행").isEqualTo((int) dbRows);
        // 실패 강제 요청은 Redis 에서 SUCCESS 를 받으면 DB 예외, 재고가 잠깐 바닥난 순간이면 SOLD_OUT 을 받는다
        assertThat(result.outcomes().keySet()).as("나올 수 있는 응답만")
                .isSubsetOf("SUCCESS", "COUPON_SOLD_OUT", "UNEXPECTED:DataIntegrityViolationException");
        assertThat(result.count("UNEXPECTED:DataIntegrityViolationException")).as("DB 예외는 실패 강제 요청에서만")
                .isPositive().isLessThanOrEqualTo(FAILING_REQUESTS);
        assertThat(result.outcomes().values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum())
                .as("모든 요청이 응답됨").isEqualTo(VALID_USERS + FAILING_REQUESTS);
    }

    @Test
    void 보상으로_되돌린_재고는_남아_있는_사용자가_가져간다() {
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, STOCK);
        List<Long> validUserIds = createUsers(jdbcTemplate, VALID_USERS);

        // 실패 요청이 먼저 재고를 전부 잡았다가 보상으로 돌려준 상황
        for (long i = 1; i <= STOCK; i++) {
            try {
                issuer.issue(coupon.getId(), 50_000_000L + i);
            } catch (RuntimeException ignored) {
                // FK 위반 → 보상
            }
        }
        assertThat(redisStock.getStock(coupon.getId())).as("모두 보상됨").contains(STOCK);

        validUserIds.forEach(userId -> {
            try {
                issuer.issue(coupon.getId(), userId);
            } catch (RuntimeException ignored) {
                // 100명 이후는 SOLD_OUT
            }
        });
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(STOCK);
        assertThat(redisStock.getStock(coupon.getId())).contains(0);
    }
}

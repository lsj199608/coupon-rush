package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.ConcurrencyTestSupport.Result;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선착순 발급의 핵심 요구사항 검증: 대량 동시 요청에서도 정확히 재고만큼만 발급되는가.
 * - 모든 동시성 제어 방식(IssueStrategy)이 같은 시나리오를 통과해야 한다
 * - Testcontainers PostgreSQL 사용 (H2 는 행 락/MVCC 동작이 달라 쓰지 않는다)
 * - 서비스(발급기)를 직접 호출한다 (HTTP/인증 오버헤드 없이 발급 로직의 동시성만 본다)
 * 소요 시간 비교는 CouponIssueStrategyBenchmarkTest (./gradlew benchmark)
 */
@IntegrationTest
class CouponIssueConcurrencyTest {

    private static final int TOTAL_QUANTITY = 100;
    private static final int REQUEST_COUNT = 1000;
    private static final int THREAD_COUNT = 32;

    @Autowired List<CouponIssuer> issuers;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        cleanUp(jdbcTemplate);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 재고_100개에_1000명이_동시에_요청해도_정확히_100명만_발급된다(IssueStrategy strategy) throws Exception {
        CouponIssuer issuer = issuer(issuers, strategy);
        Coupon coupon = saveOpenCoupon(couponRepository, TOTAL_QUANTITY);
        List<Long> userIds = createUsers(jdbcTemplate, REQUEST_COUNT);

        Result result = runConcurrently(userIds.stream()
                .map(userId -> (Runnable) () -> issuer.issue(coupon.getId(), userId))
                .toList(), THREAD_COUNT);

        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        result.print(strategy + " / 재고 " + TOTAL_QUANTITY + " / 요청 " + REQUEST_COUNT, issuedRows, issuedQuantity);

        assertThat(result.count("SUCCESS")).as("성공 응답 수").isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedRows).as("coupon_issues 행 수 (실제 발급)").isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedQuantity).as("coupons.issued_quantity").isEqualTo(TOTAL_QUANTITY);
        assertThat(result.count("COUPON_SOLD_OUT")).as("소진 응답 수").isEqualTo(REQUEST_COUNT - TOTAL_QUANTITY);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 같은_사용자가_동시에_10번_요청해도_1번만_발급되고_나머지는_이미_발급됨으로_응답한다(IssueStrategy strategy) throws Exception {
        CouponIssuer issuer = issuer(issuers, strategy);
        Coupon coupon = saveOpenCoupon(couponRepository, TOTAL_QUANTITY);
        Long userId = createUsers(jdbcTemplate, 1).get(0);

        Result result = runConcurrently(IntStream.range(0, 10)
                .mapToObj(i -> (Runnable) () -> issuer.issue(coupon.getId(), userId))
                .toList(), THREAD_COUNT);

        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        result.print(strategy + " / 같은 사용자 동시 10회", issuedRows, issuedQuantity);

        assertThat(result.count("SUCCESS")).isEqualTo(1);
        assertThat(result.count("COUPON_ALREADY_ISSUED")).isEqualTo(9);
        assertThat(issuedRows).isEqualTo(1);
        assertThat(issuedQuantity).isEqualTo(1);
    }
}

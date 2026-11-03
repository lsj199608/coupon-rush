package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.global.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선착순 발급의 핵심 요구사항 검증: 대량 동시 요청에서도 정확히 재고만큼만 발급되는가.
 * 서비스 메서드를 직접 호출한다 (HTTP/인증 오버헤드 없이 발급 로직의 동시성만 본다).
 * 4단계(Redis)에서도 같은 시나리오로 비교한다.
 */
@IntegrationTest
class CouponIssueConcurrencyTest {

    private static final int TOTAL_QUANTITY = 100;
    private static final int REQUEST_COUNT = 1000;
    private static final int THREAD_COUNT = 32;

    @Autowired CouponIssueService couponIssueService;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM coupon_issues");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void 재고_100개에_1000명이_동시에_요청해도_정확히_100명만_발급된다() throws Exception {
        Coupon coupon = saveCoupon(TOTAL_QUANTITY);
        List<Long> userIds = createUsers(REQUEST_COUNT);

        Result result = runConcurrently(userIds.stream()
                .map(userId -> (Runnable) () -> couponIssueService.issue(coupon.getId(), userId))
                .toList());

        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        result.print("재고 " + TOTAL_QUANTITY + "개 / 요청 " + REQUEST_COUNT + "건 / 스레드 " + THREAD_COUNT,
                issuedRows, issuedQuantity);

        assertThat(result.count("SUCCESS")).as("성공 응답 수").isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedRows).as("coupon_issues 행 수 (실제 발급)").isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedQuantity).as("coupons.issued_quantity").isEqualTo(TOTAL_QUANTITY);
        assertThat(result.count("COUPON_SOLD_OUT")).as("소진 응답 수").isEqualTo(REQUEST_COUNT - TOTAL_QUANTITY);
    }

    @Test
    void 같은_사용자가_동시에_10번_요청해도_1번만_발급되고_나머지는_이미_발급됨으로_응답한다() throws Exception {
        Coupon coupon = saveCoupon(TOTAL_QUANTITY);
        Long userId = createUsers(1).get(0);

        Result result = runConcurrently(IntStream.range(0, 10)
                .mapToObj(i -> (Runnable) () -> couponIssueService.issue(coupon.getId(), userId))
                .toList());

        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();
        result.print("같은 사용자 동시 10회", issuedRows, issuedQuantity);

        assertThat(result.count("SUCCESS")).isEqualTo(1);
        assertThat(result.count("COUPON_ALREADY_ISSUED")).isEqualTo(9);
        assertThat(issuedRows).isEqualTo(1);
        assertThat(issuedQuantity).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------

    private Coupon saveCoupon(int totalQuantity) {
        Instant now = Instant.now();
        return couponRepository.save(Coupon.create("동시성 테스트 쿠폰", totalQuantity,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));
    }

    /** JPA 로 1000건 저장하면 느려서 JDBC 배치로 넣는다 */
    private List<Long> createUsers(int count) {
        String prefix = "concurrency-" + System.nanoTime() + "-";
        jdbcTemplate.batchUpdate("""
                        INSERT INTO users (provider, provider_id, nickname, role, created_at, updated_at)
                        VALUES ('KAKAO', ?, ?, 'USER', now(), now())
                        """,
                IntStream.range(0, count).mapToObj(i -> new Object[]{prefix + i, "user" + i}).toList());
        return jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE provider_id LIKE ? ORDER BY id", Long.class, prefix + "%");
    }

    /** 모든 작업을 준비시킨 뒤 동시에 출발시킨다 (CountDownLatch 로 출발선 정렬) */
    private Result runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        long startedAt = System.nanoTime();

        for (Runnable task : tasks) {
            executor.submit(() -> {
                try {
                    start.await();
                    task.run();
                    record(outcomes, "SUCCESS");
                } catch (BusinessException e) {
                    record(outcomes, e.getErrorCode().name());
                } catch (Exception e) {
                    // 예상하지 못한 실패(DB 제약 위반 등)도 숨기지 않고 종류별로 집계한다
                    record(outcomes, "UNEXPECTED:" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertThat(finished).as("60초 안에 모든 요청이 끝나야 한다").isTrue();

        return new Result(new TreeMap<>(outcomes), Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static void record(Map<String, AtomicInteger> outcomes, String key) {
        outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private record Result(Map<String, AtomicInteger> outcomes, Duration elapsed) {

        int count(String key) {
            AtomicInteger value = outcomes.get(key);
            return value == null ? 0 : value.get();
        }

        void print(String scenario, long issuedRows, int issuedQuantity) {
            List<String> lines = new ArrayList<>();
            lines.add("========== [동시성 결과] " + scenario + " ==========");
            outcomes.forEach((k, v) -> lines.add(String.format("  %-34s %5d", k, v.get())));
            lines.add(String.format("  %-34s %5d", "coupon_issues 행 수 (실제 발급)", issuedRows));
            lines.add(String.format("  %-34s %5d", "coupons.issued_quantity", issuedQuantity));
            lines.add(String.format("  %-34s %5d ms", "소요 시간", elapsed.toMillis()));
            lines.add("=".repeat(60));
            System.out.println(String.join(System.lineSeparator(), lines));
        }
    }
}

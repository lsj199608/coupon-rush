package com.couponrush.coupon;

import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.global.error.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
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

/** 동시성 테스트/벤치마크 공통 도구 */
final class ConcurrencyTestSupport {

    private ConcurrencyTestSupport() {
    }

    static CouponIssuer issuer(List<CouponIssuer> issuers, IssueStrategy strategy) {
        return issuers.stream().filter(i -> i.strategy() == strategy).findFirst().orElseThrow();
    }

    /** 발급 기간 중인 쿠폰을 DB 에 저장하고 Redis 에도 재고를 적재한다 (실제 생성 API 와 같은 상태) */
    static Coupon saveOpenCoupon(CouponRepository couponRepository, RedisCouponStock redisStock, int totalQuantity) {
        Instant now = Instant.now();
        return saveAndLoad(couponRepository, redisStock, Coupon.create("동시성 테스트 쿠폰", totalQuantity,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));
    }

    static Coupon saveAndLoad(CouponRepository couponRepository, RedisCouponStock redisStock, Coupon coupon) {
        Coupon saved = couponRepository.save(coupon);
        redisStock.load(saved, List.of());
        return saved;
    }

    /** JPA 로 1000건 저장하면 느려서 JDBC 배치로 넣는다 */
    static List<Long> createUsers(JdbcTemplate jdbcTemplate, int count) {
        String prefix = "concurrency-" + System.nanoTime() + "-";
        jdbcTemplate.batchUpdate("""
                        INSERT INTO users (provider, provider_id, nickname, role, created_at, updated_at)
                        VALUES ('KAKAO', ?, ?, 'USER', now(), now())
                        """,
                IntStream.range(0, count).mapToObj(i -> new Object[]{prefix + i, "user" + i}).toList());
        return jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE provider_id LIKE ? ORDER BY id", Long.class, prefix + "%");
    }

    static void cleanUp(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        jdbcTemplate.update("DELETE FROM coupon_issues");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("event:*"));
    }

    /**
     * 모든 작업을 스레드에 올려 둔 뒤 CountDownLatch 로 동시에 출발시킨다.
     * 소요 시간은 출발 신호부터 마지막 작업 종료까지 (회원 생성 등 준비 시간 제외).
     */
    static Result runConcurrently(List<Runnable> tasks, int threadCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(Math.min(threadCount, tasks.size()));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

        for (Runnable task : tasks) {
            executor.submit(() -> {
                try {
                    ready.countDown();
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

        ready.await(10, TimeUnit.SECONDS);   // 스레드들이 출발선에 설 때까지
        long startedAt = System.nanoTime();
        start.countDown();
        boolean finished = done.await(300, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startedAt;
        executor.shutdownNow();
        if (!finished) {
            throw new AssertionError("300초 안에 모든 요청이 끝나지 않았습니다.");
        }
        return new Result(new TreeMap<>(outcomes), Duration.ofNanos(elapsedNanos));
    }

    private static void record(Map<String, AtomicInteger> outcomes, String key) {
        outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    record Result(Map<String, AtomicInteger> outcomes, Duration elapsed) {

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

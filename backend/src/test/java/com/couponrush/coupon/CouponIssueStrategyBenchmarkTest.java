package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.ConcurrencyTestSupport.Result;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.coupon.issue.OptimisticLockCouponIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 제어 방식별 소요 시간 비교 (요청 1000건 / 32스레드).
 *  - 시나리오 A: 재고 100  → 100명 성공 후 900명은 소진 응답 (기본 동시성 테스트와 동일)
 *  - 시나리오 B: 재고 1000 → 1000명 전원이 같은 행을 끝까지 경쟁 (최대 경합)
 * 워밍업 1회는 버리고 측정 3회. 매 회 "초과 발급 없음"을 검증한다.
 * 시간이 걸려 기본 test 에서는 제외: ./gradlew benchmark → build/benchmark/coupon-issue-strategies.md
 */
@Tag("benchmark")
@IntegrationTest
class CouponIssueStrategyBenchmarkTest {

    private static final int REQUEST_COUNT = 1000;
    private static final int THREAD_COUNT = 32;
    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURE_ROUNDS = 3;
    private static final int[] STOCKS = {100, 1000};

    @Autowired List<CouponIssuer> issuers;
    @Autowired OptimisticLockCouponIssuer optimisticIssuer;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        cleanUp(jdbcTemplate);
    }

    @Test
    void 방식별_소요_시간_비교() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(String.format("요청 %d / 스레드 %d / 측정 %d회 (워밍업 %d회 제외)%n",
                REQUEST_COUNT, THREAD_COUNT, MEASURE_ROUNDS, WARMUP_ROUNDS));

        for (int stock : STOCKS) {
            List<Row> rows = new ArrayList<>();
            for (IssueStrategy strategy : IssueStrategy.values()) {
                rows.add(measure(strategy, stock));
            }
            report.append(String.format("%n### 재고 %d / 요청 %d%n%n", stock, REQUEST_COUNT));
            report.append("| 방식 | 평균 (ms) | 최소 | 최대 | 성공 (평균) | 재시도 초과 실패 (평균) | 버전 충돌 (평균) |\n");
            report.append("|---|---:|---:|---:|---:|---:|---:|\n");
            rows.forEach(r -> report.append(r.toMarkdown()));
        }

        System.out.println(report);
        Path out = Path.of("build", "benchmark", "coupon-issue-strategies.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    }

    private Row measure(IssueStrategy strategy, int stock) throws InterruptedException {
        List<Long> elapsed = new ArrayList<>();
        List<Integer> successes = new ArrayList<>();
        List<Integer> exhausted = new ArrayList<>();
        List<Long> conflicts = new ArrayList<>();

        for (int round = 0; round < WARMUP_ROUNDS + MEASURE_ROUNDS; round++) {
            long conflictsBefore = optimisticIssuer.getConflictCount();
            Result result = runOnce(strategy, stock);
            if (round < WARMUP_ROUNDS) {
                continue;
            }
            elapsed.add(result.elapsed().toMillis());
            successes.add(result.count("SUCCESS"));
            exhausted.add(result.count("COUPON_ISSUE_CONFLICT"));
            conflicts.add(optimisticIssuer.getConflictCount() - conflictsBefore);
        }
        return new Row(strategy, elapsed, successes, exhausted, conflicts);
    }

    private Result runOnce(IssueStrategy strategy, int stock) throws InterruptedException {
        CouponIssuer issuer = issuer(issuers, strategy);
        Coupon coupon = saveOpenCoupon(couponRepository, stock);
        List<Long> userIds = createUsers(jdbcTemplate, REQUEST_COUNT);

        Result result = runConcurrently(userIds.stream()
                .map(userId -> (Runnable) () -> issuer.issue(coupon.getId(), userId))
                .toList(), THREAD_COUNT);

        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();

        // 어떤 방식이든 "초과 발급 없음"과 "카운터 = 실제 발급"은 반드시 지켜야 한다
        assertThat(issuedRows).as(strategy + " 초과 발급 없음").isLessThanOrEqualTo(stock);
        assertThat(issuedRows).as(strategy + " 성공 응답 = 발급 행").isEqualTo(result.count("SUCCESS"));
        assertThat(issuedQuantity).as(strategy + " 카운터 = 발급 행").isEqualTo((int) issuedRows);
        assertThat(result.outcomes().keySet()).as(strategy + " 예상 못한 예외 없음")
                .noneMatch(k -> k.startsWith("UNEXPECTED"));
        if (stock < REQUEST_COUNT) {
            assertThat(result.count("SUCCESS")).as(strategy + " 재고만큼 성공").isEqualTo(stock);
        }

        cleanUp(jdbcTemplate);
        return result;
    }

    private record Row(IssueStrategy strategy, List<Long> elapsed, List<Integer> successes,
                       List<Integer> exhausted, List<Long> conflicts) {

        String toMarkdown() {
            boolean optimistic = strategy == IssueStrategy.OPTIMISTIC_LOCK;
            return String.format("| %s | %.0f | %d | %d | %.0f | %s | %s |%n",
                    strategy,
                    elapsed.stream().mapToLong(Long::longValue).average().orElse(0),
                    elapsed.stream().mapToLong(Long::longValue).min().orElse(0),
                    elapsed.stream().mapToLong(Long::longValue).max().orElse(0),
                    successes.stream().mapToInt(Integer::intValue).average().orElse(0),
                    optimistic ? String.format("%.0f", exhausted.stream().mapToInt(Integer::intValue).average().orElse(0)) : "-",
                    optimistic ? String.format("%.0f", conflicts.stream().mapToLong(Long::longValue).average().orElse(0)) : "-");
        }
    }
}

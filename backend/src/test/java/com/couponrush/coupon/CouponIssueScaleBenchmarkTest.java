package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.ConcurrencyTestSupport.Result;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규모별 비교: 재고 100 고정, 요청 1,000명 / 10,000명, 그리고 "같은 사람이 여러 번 동시에 요청"하는 부하.
 * 매 실행마다 초과 발급 0건, 중복 발급 0건을 DB(와 Redis)에서 직접 검증한다.
 * Redis 는 Testcontainers 컨테이너(redis:7.4-alpine)를 쓴다 (TestcontainersConfiguration).
 *
 * 시간이 오래 걸려 기본 test 에서는 제외: ./gradlew benchmark → build/benchmark/coupon-issue-scale.md
 */
@Tag("benchmark")
@IntegrationTest
class CouponIssueScaleBenchmarkTest {

    private static final int STOCK = 100;
    private static final int THREAD_COUNT = 32;
    private static final int WARMUP_USERS = 1_000;
    private static final int MEASURE_ROUNDS_1K = 3;

    /** 비교 대상 (조건부 UPDATE 는 DB 기본 방식이라 참고용으로 함께 측정) */
    private static final List<IssueStrategy> STRATEGIES = List.of(
            IssueStrategy.PESSIMISTIC_LOCK, IssueStrategy.OPTIMISTIC_LOCK, IssueStrategy.REDIS, IssueStrategy.CONDITIONAL_UPDATE);

    @Autowired List<CouponIssuer> issuers;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisCouponStock redisStock;

    @AfterEach
    void tearDown() {
        cleanUp(jdbcTemplate, redisTemplate);
    }

    @Test
    void 규모별_소요_시간과_정합성() throws Exception {
        Map<IssueStrategy, Map<String, String>> table = new EnumMap<>(IssueStrategy.class);

        for (IssueStrategy strategy : STRATEGIES) {
            Map<String, String> row = new LinkedHashMap<>();
            run(strategy, WARMUP_USERS, 1);   // 워밍업 (기록하지 않음)

            List<Long> elapsed1k = new ArrayList<>();
            for (int i = 0; i < MEASURE_ROUNDS_1K; i++) {
                elapsed1k.add(run(strategy, 1_000, 1).elapsed().toMillis());
            }
            row.put("1k", String.format("%,d", Math.round(elapsed1k.stream().mapToLong(Long::longValue).average().orElse(0))));
            row.put("10k", String.format("%,d", run(strategy, 10_000, 1).elapsed().toMillis()));

            Result duplicated = run(strategy, 1_000, 3);   // 1,000명 × 3회 = 3,000 요청
            row.put("dup", String.format("%,d", duplicated.elapsed().toMillis()));
            row.put("dupOutcome", String.format("성공 %d / 이미발급 %d / 소진 %d",
                    duplicated.count("SUCCESS"), duplicated.count("COUPON_ALREADY_ISSUED"), duplicated.count("COUPON_SOLD_OUT")));
            table.put(strategy, row);
        }

        String report = toMarkdown(table);
        System.out.println(report);
        Path out = Path.of("build", "benchmark", "coupon-issue-scale.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
    }

    /**
     * @param users           서로 다른 사용자 수
     * @param requestsPerUser 사용자당 동시 요청 수 (2 이상이면 중복 요청 부하)
     */
    private Result run(IssueStrategy strategy, int users, int requestsPerUser) throws InterruptedException {
        CouponIssuer issuer = issuer(issuers, strategy);
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, STOCK);
        List<Long> userIds = createUsers(jdbcTemplate, users);

        List<Runnable> tasks = new ArrayList<>(users * requestsPerUser);
        for (int i = 0; i < requestsPerUser; i++) {
            userIds.forEach(userId -> tasks.add(() -> issuer.issue(coupon.getId(), userId)));
        }
        Collections.shuffle(tasks, new Random(42));   // 같은 사람의 요청이 붙어서 오지 않게 섞는다

        Result result = runConcurrently(tasks, THREAD_COUNT);
        verify(strategy, coupon, result, users * requestsPerUser);
        cleanUp(jdbcTemplate, redisTemplate);
        return result;
    }

    private void verify(IssueStrategy strategy, Coupon coupon, Result result, int requestCount) {
        String label = strategy + " (" + requestCount + "건) ";
        long issuedRows = couponIssueRepository.countByCouponId(coupon.getId());
        Integer duplicatedUsers = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                    SELECT user_id FROM coupon_issues WHERE coupon_id = ? GROUP BY user_id HAVING count(*) > 1
                ) d
                """, Integer.class, coupon.getId());
        int issuedQuantity = couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity();

        assertThat(issuedRows).as(label + "초과 발급 0건 (발급 행 = 재고)").isEqualTo(STOCK);
        assertThat(duplicatedUsers).as(label + "중복 발급 0건 (한 사람이 2장 이상)").isZero();
        assertThat(result.count("SUCCESS")).as(label + "성공 응답 = 발급 행").isEqualTo(STOCK);
        assertThat(issuedQuantity).as(label + "DB 카운터 = 발급 행").isEqualTo(STOCK);
        assertThat(result.outcomes().keySet()).as(label + "예상 못한 예외 없음").noneMatch(k -> k.startsWith("UNEXPECTED"));
        assertThat(result.outcomes().values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum())
                .as(label + "모든 요청이 응답됨").isEqualTo(requestCount);

        if (strategy == IssueStrategy.REDIS) {
            assertThat(redisStock.getStock(coupon.getId())).as(label + "Redis 남은 재고").contains(0);
            assertThat(redisStock.getIssuedCount(coupon.getId())).as(label + "Redis Set = DB 발급 행").isEqualTo(STOCK);
        }
    }

    private static String toMarkdown(Map<IssueStrategy, Map<String, String>> table) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("재고 %d / 스레드 %d / 1,000명은 %d회 평균(워밍업 1회 제외), 나머지는 1회%n",
                STOCK, THREAD_COUNT, MEASURE_ROUNDS_1K));
        sb.append("모든 실행에서 검증: 초과 발급 0 / 중복 발급 0 / 성공 = 발급 행 = DB 카운터 (Redis 는 남은 재고 0, Set = 100)\n\n");
        sb.append("| 방식 | 1,000명 (ms) | 10,000명 (ms) | 1,000명 × 3회 (ms) | 1,000명 × 3회 결과 |\n");
        sb.append("|---|---:|---:|---:|---|\n");
        table.forEach((strategy, row) -> sb.append(String.format("| %s | %s | %s | %s | %s |%n",
                strategy, row.get("1k"), row.get("10k"), row.get("dup"), row.get("dupOutcome"))));
        return sb.toString();
    }
}

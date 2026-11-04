package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.coupon.issue.async.AsyncCouponIssueRequester;
import com.couponrush.coupon.issue.async.CouponIssueKafkaConfig;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.IssueRequestStatus;
import com.couponrush.global.error.BusinessException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.stream.IntStream;

import static com.couponrush.coupon.ConcurrencyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 동기 Redis vs Kafka 비동기 측정. 두 가지 시간을 구분한다.
 *  - API 응답 시간: 요청 하나가 응답(201 또는 202)을 받을 때까지 — 사용자가 체감하는 시간. p50 / p95 / p99 / 최대
 *  - 전체 완료 시간: 출발 신호부터 모든 발급이 DB 에 반영될 때까지 (동기는 마지막 응답 = 완료)
 * 그리고 컨슈머 파티션 / 컨커런시를 바꿔 DB 반영 처리량을 비교한다.
 *
 * 조건: 32 스레드, HikariCP 풀 10, Testcontainers (Kafka 1 브로커, PostgreSQL, Redis), 로컬 Windows + Docker Desktop.
 * 워밍업: 측정 전에 각 방식으로 작은 실행(재고 50 / 200명)을 1번 해서 JIT, 커넥션 풀, Kafka Producer 메타데이터를 데운다.
 * 각 시나리오는 1회 측정 (회차 간 편차는 이전 측정에서 ±30% 안팎).
 * ./gradlew benchmark --tests "com.couponrush.coupon.AsyncIssueBenchmarkTest" → build/benchmark/coupon-issue-async.md
 */
@Tag("benchmark")
@IntegrationTest
class AsyncIssueBenchmarkTest {

    private static final int THREAD_COUNT = 32;
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(4);

    @Autowired List<CouponIssuer> issuers;
    @Autowired AsyncCouponIssueRequester requester;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    private final StringBuilder report = new StringBuilder();

    @AfterEach
    void tearDown() throws Exception {
        cleanUp(jdbcTemplate, redisTemplate);
        Path out = Path.of("build", "benchmark", "coupon-issue-async.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println(report);
    }

    // ================================================================= 1. 동기 Redis vs Kafka 비동기

    @Test
    void 동기_Redis_와_Kafka_비동기의_응답_시간과_완료_시간() throws Exception {
        CouponIssuer syncRedis = issuer(issuers, IssueStrategy.REDIS);

        // 워밍업 (기록하지 않음)
        runSync(syncRedis, 50, 200);
        runAsync(50, 200);

        int[][] scenarios = {{100, 1_000}, {100, 10_000}, {1_000, 1_000}};
        List<Row> rows = new ArrayList<>();
        for (int[] s : scenarios) {
            rows.add(runSync(syncRedis, s[0], s[1]));
            rows.add(runAsync(s[0], s[1]));
        }

        report.append("\n## 1. 동기 Redis vs Kafka 비동기 (파티션 3 / 컨커런시 3)\n\n");
        report.append("32 스레드, 워밍업 1회 후 시나리오별 1회 측정. 응답 시간 = 요청 1건이 응답받기까지, 완료 = 출발부터 모든 발급이 DB 반영될 때까지\n\n");
        report.append("| 시나리오 | 방식 | 응답 p50 | p95 | p99 | 최대 | 모든 응답 반환 | 전체 완료 (DB 반영) |\n");
        report.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        rows.forEach(r -> report.append(r.toMarkdown()));
    }

    // ================================================================= 2. 파티션 / 컨커런시

    @Test
    void 컨슈머_파티션과_컨커런시에_따른_DB_반영_처리량() throws Exception {
        int[][] configs = {{3, 1}, {3, 3}, {6, 6}};   // {파티션, 컨커런시} — 파티션은 늘리기만 가능하므로 이 순서
        List<String> lines = new ArrayList<>();

        runAsync(50, 200);   // 워밍업
        for (int[] config : configs) {
            applyConsumerConfig(config[0], config[1]);
            runAsync(50, 200);   // 설정 변경 후 워밍업 (리밸런싱 직후 첫 메시지 지연 제외)

            Row single = runAsync(1_000, 1_000);
            Row multi = runAsyncMultiEvent(6, 200, config[0]);
            lines.add(String.format("| %d | %d | 이벤트 1개 (재고 1000 / 1000명) | %,d ms | %.0f건/s | %s |%n",
                    config[0], config[1], single.completeMillis(), 1_000 * 1000.0 / single.completeMillis(), single.partitionSpread()));
            lines.add(String.format("| %d | %d | 이벤트 6개 동시 (각 재고 200 / 200명) | %,d ms | %.0f건/s | %s |%n",
                    config[0], config[1], multi.completeMillis(), 1_200 * 1000.0 / multi.completeMillis(), multi.partitionSpread()));
        }
        applyConsumerConfig(6, 3);   // 원래 스레드 수로 되돌림 (파티션은 줄일 수 없다)

        report.append("\n## 2. 컨슈머 파티션 / 컨커런시 (Kafka 비동기, 전원 성공 = 모든 요청이 DB 쓰기)\n\n");
        report.append("완료 = 출발부터 모든 발급이 DB 반영될 때까지. 처리량 = DB 반영 건수 / 완료 시간. 분포 = 파티션별 메시지 수(키 = 쿠폰 ID)\n\n");
        report.append("| 파티션 | 컨커런시 | 시나리오 | 전체 완료 | 처리량 | 파티션 분포 |\n");
        report.append("|---:|---:|---|---:|---:|---|\n");
        lines.forEach(report::append);
    }

    // ================================================================= 실행

    private Row runSync(CouponIssuer issuer, int stock, int users) throws Exception {
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, stock);
        List<Long> userIds = createUsers(jdbcTemplate, users);

        Timed timed = runTimed(userIds.size(), i -> issuer.issue(coupon.getId(), userIds.get(i)));
        // 동기는 마지막 응답 = 모든 DB 반영 완료
        verify(coupon, stock);
        cleanUp(jdbcTemplate, redisTemplate);
        return new Row(stock, users, "동기 Redis (201)", timed, timed.wallMillis(), "-");
    }

    private Row runAsync(int stock, int users) throws Exception {
        Coupon coupon = saveOpenCoupon(couponRepository, redisStock, stock);
        List<Long> userIds = createUsers(jdbcTemplate, users);

        Timed timed = runTimed(userIds.size(), i -> requester.request(coupon.getId(), userIds.get(i)));
        await().atMost(COMPLETION_TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() ->
                issueRequestRepository.countByCouponIdAndStatus(coupon.getId(), IssueRequestStatus.COMPLETED) == stock);
        long completeMillis = (System.nanoTime() - timed.startNanos()) / 1_000_000;

        verify(coupon, stock);
        cleanUp(jdbcTemplate, redisTemplate);
        return new Row(stock, users, "Kafka 비동기 (202)", timed, completeMillis, partitionOf(coupon.getId()));
    }

    /** 이벤트 여러 개가 동시에 열린 상황: 쿠폰마다 다른 파티션으로 갈 수 있어 컨슈머 스레드가 병렬로 일한다 */
    private Row runAsyncMultiEvent(int events, int usersPerEvent, int partitions) throws Exception {
        List<Coupon> coupons = IntStream.range(0, events)
                .mapToObj(i -> saveOpenCoupon(couponRepository, redisStock, usersPerEvent)).toList();
        List<Long> userIds = createUsers(jdbcTemplate, events * usersPerEvent);

        Timed timed = runTimed(userIds.size(), i -> requester.request(coupons.get(i % events).getId(), userIds.get(i)));
        await().atMost(COMPLETION_TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> coupons.stream()
                .mapToLong(c -> issueRequestRepository.countByCouponIdAndStatus(c.getId(), IssueRequestStatus.COMPLETED))
                .sum() == (long) events * usersPerEvent);
        long completeMillis = (System.nanoTime() - timed.startNanos()) / 1_000_000;

        for (Coupon coupon : coupons) {
            verify(coupon, usersPerEvent);
        }
        Map<Integer, Integer> spread = new TreeMap<>();
        coupons.forEach(c -> spread.merge(partition(c.getId(), partitions), usersPerEvent, Integer::sum));
        cleanUp(jdbcTemplate, redisTemplate);
        return new Row(usersPerEvent, events * usersPerEvent, "Kafka 비동기 (202)", timed, completeMillis, spread.toString());
    }

    /** 요청마다 응답 시간을 기록하면서 THREAD_COUNT 스레드로 동시에 보낸다 */
    private Timed runTimed(int count, java.util.function.IntConsumer call) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        long[] latencies = new long[count];
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

        for (int i = 0; i < count; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    try {
                        call.accept(index);
                        outcomes.computeIfAbsent("SUCCESS", k -> new AtomicInteger()).incrementAndGet();
                    } catch (BusinessException e) {
                        outcomes.computeIfAbsent(e.getErrorCode().name(), k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        latencies[index] = System.nanoTime() - t0;
                    }
                } catch (Exception e) {
                    outcomes.computeIfAbsent("UNEXPECTED:" + e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(200);   // 스레드들이 출발선에 서도록
        long startNanos = System.nanoTime();
        start.countDown();
        assertThat(done.await(5, TimeUnit.MINUTES)).isTrue();
        long wallMillis = (System.nanoTime() - startNanos) / 1_000_000;
        executor.shutdownNow();
        assertThat(outcomes.keySet()).noneMatch(k -> k.startsWith("UNEXPECTED"));
        Arrays.sort(latencies);
        return new Timed(startNanos, wallMillis, latencies);
    }

    private void verify(Coupon coupon, int stock) {
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).as("초과 발급 없음").isEqualTo(stock);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(stock);
        assertThat(redisStock.getStock(coupon.getId())).contains(0);
    }

    /** 파티션 수를 늘리고 컨슈머 컨테이너를 새 컨커런시로 재시작한다 */
    private void applyConsumerConfig(int partitions, int concurrency) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            int current = admin.describeTopics(List.of(CouponIssueKafkaConfig.TOPIC)).allTopicNames().get()
                    .get(CouponIssueKafkaConfig.TOPIC).partitions().size();
            if (partitions > current) {
                admin.createPartitions(Map.of(
                        CouponIssueKafkaConfig.TOPIC, NewPartitions.increaseTo(partitions),
                        CouponIssueKafkaConfig.DLT_TOPIC, NewPartitions.increaseTo(partitions))).all().get();
                // Producer 는 토픽 메타데이터를 캐시한다 → 새로 만들어야 늘어난 파티션으로 키를 나눈다
                ((DefaultKafkaProducerFactory<?, ?>) kafkaTemplate.getProducerFactory()).reset();
            }
        }
        MessageListenerContainer container = listenerRegistry.getListenerContainer(CouponIssueKafkaConfig.MAIN_LISTENER_ID);
        container.stop();
        ((ConcurrentMessageListenerContainer<?, ?>) container).setConcurrency(concurrency);
        container.start();
        ContainerTestUtils.waitForAssignment(container, partitions);
    }

    private String partitionOf(Long couponId) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            int partitions = admin.describeTopics(List.of(CouponIssueKafkaConfig.TOPIC)).allTopicNames().get()
                    .get(CouponIssueKafkaConfig.TOPIC).partitions().size();
            return "파티션 " + partition(couponId, partitions) + "에 전부";
        }
    }

    /** Kafka 기본 파티셔너와 같은 계산 (키가 있으면 murmur2 해시 % 파티션 수) */
    private static int partition(Long couponId, int partitions) {
        return Utils.toPositive(Utils.murmur2(String.valueOf(couponId).getBytes(StandardCharsets.UTF_8))) % partitions;
    }

    // ================================================================= 결과

    private record Timed(long startNanos, long wallMillis, long[] sortedLatencyNanos) {
        long percentileMillis(double p) {
            int index = (int) Math.ceil(p * sortedLatencyNanos.length) - 1;
            return sortedLatencyNanos[Math.max(index, 0)] / 1_000_000;
        }

        long maxMillis() {
            return sortedLatencyNanos[sortedLatencyNanos.length - 1] / 1_000_000;
        }
    }

    private record Row(int stock, int users, String mode, Timed timed, long completeMillis, String partitionSpread) {
        String toMarkdown() {
            LongFunction<String> ms = v -> String.format("%,d ms", v);
            return String.format("| 재고 %,d / %,d명 | %s | %s | %s | %s | %s | %s | %s |%n",
                    stock, users, mode,
                    ms.apply(timed.percentileMillis(0.50)), ms.apply(timed.percentileMillis(0.95)),
                    ms.apply(timed.percentileMillis(0.99)), ms.apply(timed.maxMillis()),
                    ms.apply(timed.wallMillis()), ms.apply(completeMillis));
        }
    }
}

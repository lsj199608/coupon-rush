package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.issue.async.CouponIssueConsumerMetrics;
import com.couponrush.coupon.issue.async.CouponIssueEventProcessor;
import com.couponrush.coupon.issue.async.CouponIssueKafkaConfig;
import com.couponrush.coupon.issue.async.CouponIssueRequestedEvent;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.issue.redis.RedisIssueResult;
import com.couponrush.coupon.request.IssueRequest;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.IssueRequestStatus;
import com.couponrush.coupon.request.PendingIssueRequestStore;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 컨슈머 신뢰성: 멱등, 지수 백오프 재시도, DLT, "DB 저장 성공 후에만 오프셋 커밋".
 * 실제 Kafka / PostgreSQL / Redis 컨테이너를 쓴다 (Testcontainers).
 *
 * DB 장애는 mock 이 아니라 실제로 일으킨다: issue_requests 테이블 이름을 잠깐 바꿔서
 * 컨슈머의 모든 쿼리가 실패하게 하고(일시 장애), 이름을 되돌리면 복구된다.
 * 오프셋은 Kafka AdminClient 로 컨슈머 그룹의 "실제 커밋된 오프셋"을 조회해서 검증한다.
 */
@IntegrationTest
class CouponIssueConsumerReliabilityTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired CouponIssueEventProcessor processor;
    @Autowired CouponIssueConsumerMetrics metrics;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired PendingIssueRequestStore pendingStore;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired UserRepository userRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        restoreDatabase();   // 테스트가 중간에 실패해도 다음 테스트에 장애가 남지 않게
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
    }

    // ---------------------------------------------------------------- 멱등

    @Test
    void 같은_메시지를_두_번_보내도_CouponIssue_는_한_번만_저장된다() throws Exception {
        Coupon coupon = openCoupon(5);
        CouponIssueRequestedEvent event = acceptedEvent(coupon, newUser());
        long duplicatesBefore = metrics.duplicatesSkipped();

        publish(event);
        publish(event);   // Producer 재전송 / 컨슈머 재처리 흉내

        await().atMost(WAIT).until(() -> metrics.duplicatesSkipped() > duplicatesBefore);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(1);
        assertThat(status(event)).isEqualTo(IssueRequestStatus.COMPLETED);
        assertThat(redisStock.getStock(coupon.getId())).as("중복 메시지는 보상하지 않는다").contains(4);
    }

    @Test
    void 같은_요청이_동시에_처리되면_유니크_제약_위반을_정상_케이스로_처리한다() throws Exception {
        Coupon coupon = openCoupon(5);
        CouponIssueRequestedEvent event = acceptedEvent(coupon, newUser());
        long duplicatesBefore = metrics.duplicatesSkipped();

        // 리밸런싱 직후처럼 같은 메시지를 여러 스레드가 동시에 처리 → 사전 확인(existsById)을 함께 통과할 수 있다
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                processor.process(event);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);   // 어느 스레드도 예외를 던지지 않아야 한다 (= 오프셋이 정상 커밋될 상황)
        }
        executor.shutdown();

        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).as("한 번만 저장").isEqualTo(1);
        assertThat(status(event)).isEqualTo(IssueRequestStatus.COMPLETED);
        assertThat(metrics.duplicatesSkipped() - duplicatesBefore).as("나머지는 중복으로 건너뜀").isEqualTo(threads - 1);
        assertThat(redisStock.getStock(coupon.getId())).as("FAILED 처리·보상 없음").contains(4);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 재시도 + 오프셋 커밋 시점

    @Test
    void 일시적_DB_장애는_재시도로_복구되고_저장에_성공하기_전에는_오프셋이_커밋되지_않는다() throws Exception {
        Coupon coupon = openCoupon(5);
        CouponIssueRequestedEvent event = acceptedEvent(coupon, newUser());
        long failuresBefore = metrics.failedDeliveries();
        long dltBefore = metrics.dltPublished();

        breakDatabase();
        RecordMetadata sent = publish(event);
        TopicPartition partition = new TopicPartition(CouponIssueKafkaConfig.TOPIC, sent.partition());

        await().atMost(WAIT).until(() -> metrics.failedDeliveries() > failuresBefore);   // 첫 실패 → 재시도 대기 중
        assertThat(committedOffset(partition)).as("처리 전에는 이 메시지를 넘어서 커밋하지 않는다")
                .isLessThanOrEqualTo(sent.offset());

        restoreDatabase();   // 백오프 중에 DB 복구 → 다음 재시도에서 성공

        await().atMost(WAIT).until(() -> status(event) == IssueRequestStatus.COMPLETED);
        await().atMost(WAIT).until(() -> committedOffset(partition) == sent.offset() + 1);   // 성공 후에 커밋
        assertThat(metrics.dltPublished()).as("DLT 로 가지 않음").isEqualTo(dltBefore);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- DLT

    @Test
    void 재시도가_모두_실패하면_DLT_로_보내고_DB_가_복구되면_FAILED_로_표시하고_재고를_되돌린다() throws Exception {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        CouponIssueRequestedEvent event = acceptedEvent(coupon, user);
        pendingStore.save(event.requestId(), coupon.getId(), user.getId(), event.requestedAt());
        long failuresBefore = metrics.failedDeliveries();
        long dltBefore = metrics.dltPublished();

        breakDatabase();
        long publishedAt = System.nanoTime();
        RecordMetadata sent = publish(event);
        TopicPartition partition = new TopicPartition(CouponIssueKafkaConfig.TOPIC, sent.partition());

        // 최초 시도 실패 → 0.5s → 1s → 2s 대기하며 재시도 3번 → 모두 실패 → DLT
        await().atMost(WAIT).until(() -> metrics.dltPublished() > dltBefore);
        long untilDltMillis = (System.nanoTime() - publishedAt) / 1_000_000;
        assertThat(metrics.failedDeliveries() - failuresBefore).as("실패한 시도 = 최초 1 + 재시도 3").isEqualTo(4);
        assertThat(untilDltMillis).as("지수 백오프 대기 합(0.5 + 1 + 2초) 이상 걸린다").isGreaterThanOrEqualTo(3_500);

        ConsumerRecord<String, String> dead = readFromDlt(event.requestId());
        assertThat(dead.partition()).as("원본과 같은 파티션").isEqualTo(sent.partition());
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).as("실패 원인이 헤더에 남는다").isNotNull();
        await().atMost(WAIT).until(() -> committedOffset(partition) == sent.offset() + 1);   // DLT 로 넘긴 뒤에야 커밋

        assertThat(redisStock.getStock(coupon.getId())).as("DB 가 죽어 있는 동안은 아직 보상 안 함").contains(4);

        restoreDatabase();   // DLT 컨슈머가 2초 간격 재시도 중 → 복구되면 FAILED 확정 + 보상

        await().atMost(WAIT).until(() -> status(event) == IssueRequestStatus.FAILED);
        IssueRequest failed = issueRequestRepository.findById(event.requestId()).orElseThrow();
        assertThat(failed.getFailureReason()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(redisStock.getStock(coupon.getId())).as("재고 복구").contains(5);
        assertThat(redisStock.getIssuedCount(coupon.getId())).as("Set 에서 제거 → 다시 신청 가능").isZero();
        assertThat(pendingStore.find(event.requestId())).as("PENDING 정리").isEmpty();
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isZero();
    }

    @Test
    void 형식이_깨진_메시지는_재시도_없이_바로_DLT_로_간다() throws Exception {
        long failuresBefore = metrics.failedDeliveries();
        long dltBefore = metrics.dltPublished();
        String marker = "broken-" + UUID.randomUUID();

        kafkaTemplate.send(CouponIssueKafkaConfig.TOPIC, "1", "{not-json " + marker).get();

        await().atMost(WAIT).until(() -> metrics.dltPublished() > dltBefore);
        assertThat(metrics.failedDeliveries() - failuresBefore).as("실패 1번 후 재시도 없이 바로 DLT").isEqualTo(1);
        assertThat(readFromDlt(marker).value()).contains(marker);   // 조사용으로 원본 보존
    }

    // ---------------------------------------------------------------- helpers

    /** Redis 판정까지 통과한 상태의 이벤트 (실제 요청 경로와 같은 전제) */
    private CouponIssueRequestedEvent acceptedEvent(Coupon coupon, User user) {
        Instant now = Instant.now();
        assertThat(redisStock.tryIssue(coupon.getId(), user.getId(), now)).isEqualTo(RedisIssueResult.SUCCESS);
        return new CouponIssueRequestedEvent(UUID.randomUUID().toString(), coupon.getId(), user.getId(), now);
    }

    private RecordMetadata publish(CouponIssueRequestedEvent event) throws Exception {
        return kafkaTemplate.send(CouponIssueKafkaConfig.TOPIC, String.valueOf(event.eventId()),
                objectMapper.writeValueAsString(event)).get().getRecordMetadata();
    }

    private IssueRequestStatus status(CouponIssueRequestedEvent event) {
        return issueRequestRepository.findById(event.requestId()).map(IssueRequest::getStatus).orElse(IssueRequestStatus.PENDING);
    }

    /** 컨슈머 그룹이 실제로 커밋한 오프셋 (= 다음에 읽을 위치). 아직 커밋이 없으면 -1 */
    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin
                    .listConsumerGroupOffsets(CouponIssueKafkaConfig.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    /** 별도 그룹으로 DLT 를 처음부터 읽어 marker 가 들어 있는 메시지를 찾는다 */
    private ConsumerRecord<String, String> readFromDlt(String marker) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(CouponIssueKafkaConfig.DLT_TOPIC));
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(marker)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("DLT 에서 메시지를 찾지 못했습니다: " + marker);
    }

    /** 일시적 DB 장애 흉내: 컨슈머가 쓰는 테이블을 잠시 없앤다 (모든 쿼리가 실패) */
    private void breakDatabase() {
        jdbcTemplate.execute("ALTER TABLE issue_requests RENAME TO issue_requests_broken");
    }

    private void restoreDatabase() {
        Boolean broken = jdbcTemplate.queryForObject("SELECT to_regclass('issue_requests_broken') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(broken)) {
            jdbcTemplate.execute("ALTER TABLE issue_requests_broken RENAME TO issue_requests");
        }
    }

    private Coupon openCoupon(int total) {
        return ConcurrencyTestSupport.saveOpenCoupon(couponRepository, redisStock, total);
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "reliability-" + UUID.randomUUID(), "사용자", null));
    }
}

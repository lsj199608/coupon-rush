package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.issue.async.AsyncCouponIssueRequester;
import com.couponrush.coupon.issue.async.CouponIssueKafkaConfig;
import com.couponrush.coupon.issue.async.CouponIssueRequestedEvent;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.issue.redis.RedisIssueResult;
import com.couponrush.coupon.request.IssueRequest;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.IssueRequestService;
import com.couponrush.coupon.request.IssueRequestStatus;
import com.couponrush.coupon.request.PendingIssueRequestStore;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 비동기 발급 파이프라인: Redis 판정 → Kafka 발행 → 컨슈머 DB 저장 → 상태 조회 (실제 Kafka/Redis/PostgreSQL 컨테이너) */
@IntegrationTest
class AsyncCouponIssueFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired AsyncCouponIssueRequester requester;
    @Autowired com.couponrush.coupon.issue.async.CouponIssueEventProcessor processor;
    @Autowired CouponIssueService couponIssueService;
    @Autowired IssueRequestService issueRequestService;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired PendingIssueRequestStore pendingStore;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired UserRepository userRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
    }

    @Test
    void 토픽은_파티션_3개로_만들어진다() {
        Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(CouponIssueKafkaConfig.TOPIC);

        assertThat(topics.get(CouponIssueKafkaConfig.TOPIC).partitions()).hasSize(3);
    }

    @Test
    void 기본_모드는_비동기다() {
        assertThat(couponIssueService.getMode()).isEqualTo(com.couponrush.coupon.issue.IssueMode.ASYNC);
    }

    @Test
    void 접수하면_PENDING_으로_조회되고_컨슈머가_저장하면_COMPLETED_가_된다() {
        Coupon coupon = openCoupon(5);
        User user = newUser();

        String requestId = requester.request(coupon.getId(), user.getId());

        assertThat(redisStock.getStock(coupon.getId())).as("Redis 는 요청 경로에서 바로 차감").contains(4);
        IssueRequestStatus first = issueRequestService.get(requestId, user.getId()).status();
        assertThat(first).isIn(IssueRequestStatus.PENDING, IssueRequestStatus.COMPLETED);   // 컨슈머가 이미 끝냈을 수도 있다

        await().atMost(WAIT).until(() -> issueRequestService.get(requestId, user.getId()).status() == IssueRequestStatus.COMPLETED);

        IssueRequest saved = issueRequestRepository.findById(requestId).orElseThrow();
        assertThat(saved.getCouponIssueId()).isNotNull();
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(1);
        assertThat(pendingStore.find(requestId)).as("처리 후 PENDING 키 삭제").isEmpty();
    }

    @Test
    void Redis_판정에서_떨어지면_Kafka_로_보내지_않고_바로_에러() {
        Coupon coupon = openCoupon(1);
        requester.request(coupon.getId(), newUser().getId());

        assertThatThrownBy(() -> requester.request(coupon.getId(), newUser().getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_SOLD_OUT);
        assertThat(redisTemplate.keys("issue-request:*")).hasSizeLessThanOrEqualTo(1);   // 성공한 1건만 PENDING
    }

    @Test
    void 같은_메시지가_두_번_와도_한_번만_저장된다_멱등() throws Exception {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        Instant now = Instant.now();
        assertThat(redisStock.tryIssue(coupon.getId(), user.getId(), now)).isEqualTo(RedisIssueResult.SUCCESS);
        CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(UUID.randomUUID().toString(), coupon.getId(), user.getId(), now);

        publish(event);
        publish(event);   // at-least-once: 재전송 흉내

        await().atMost(WAIT).until(() -> issueRequestRepository.existsById(event.requestId()));
        Thread.sleep(1_000);   // 두 번째 메시지까지 처리될 시간
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(1);
    }

    @Test
    void DB_저장이_실패하면_FAILED_로_기록되고_Redis_재고가_복구된다() {
        Coupon coupon = openCoupon(3);
        long notExistingUserId = 9_999_999L;   // FK 위반 → INSERT 실패 (탈퇴 회원 흉내)

        String requestId = requester.request(coupon.getId(), notExistingUserId);

        await().atMost(WAIT).until(() -> issueRequestRepository.findById(requestId)
                .map(r -> r.getStatus() == IssueRequestStatus.FAILED).orElse(false));
        IssueRequest failed = issueRequestRepository.findById(requestId).orElseThrow();
        assertThat(failed.getFailureReason()).isEqualTo("DATA_INTEGRITY_VIOLATION");
        assertThat(redisStock.getStock(coupon.getId())).as("재고 복구").contains(3);
        assertThat(redisStock.getIssuedCount(coupon.getId())).as("Set 에서 제거").isZero();
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isZero();
    }

    @Test
    void DB_에_이미_발급_이력이_있으면_FAILED_ALREADY_ISSUED_이고_재고만_복구된다() {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        couponIssueService.issue(coupon.getId(), user.getId());   // 동기로 먼저 발급 (Redis 4, DB 1)
        redisTemplate.opsForSet().remove(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId()));   // Set 유실 흉내

        String requestId = requester.request(coupon.getId(), user.getId());   // Redis 는 통과 (재고 3)

        await().atMost(WAIT).until(() -> issueRequestRepository.findById(requestId)
                .map(r -> r.getStatus() == IssueRequestStatus.FAILED).orElse(false));
        assertThat(issueRequestRepository.findById(requestId).orElseThrow().getFailureReason()).isEqualTo("COUPON_ALREADY_ISSUED");
        assertThat(redisStock.getStock(coupon.getId())).as("재고만 복구").contains(4);
        assertThat(redisTemplate.opsForSet().isMember(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId())))
                .as("DB 에 이력이 있으므로 Set 에는 남김").isTrue();
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    @Test
    void 컨슈머가_발급_기간이_끝난_뒤에_처리해도_요청_시각_기준으로_저장된다() throws Exception {
        Instant now = Instant.now();
        Coupon ended = ConcurrencyTestSupport.saveAndLoad(couponRepository, redisStock,
                Coupon.create("이미 끝난 이벤트", 5, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));
        User user = newUser();
        Instant requestedAt = now.minus(Duration.ofMinutes(90));   // 기간 안에 판정받았던 요청
        assertThat(redisStock.tryIssue(ended.getId(), user.getId(), requestedAt)).isEqualTo(RedisIssueResult.SUCCESS);

        publish(new CouponIssueRequestedEvent(UUID.randomUUID().toString(), ended.getId(), user.getId(), requestedAt));

        await().atMost(WAIT).until(() -> couponIssueRepository.countByCouponId(ended.getId()) == 1);
        assertThat(issueRequestRepository.countByCouponIdAndStatus(ended.getId(), IssueRequestStatus.COMPLETED)).isEqualTo(1);
    }

    @Test
    void 다른_사람의_요청이나_없는_요청은_404() {
        Coupon coupon = openCoupon(5);
        User owner = newUser();
        String requestId = requester.request(coupon.getId(), owner.getId());

        assertThatThrownBy(() -> issueRequestService.get(requestId, newUser().getId()))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_REQUEST_NOT_FOUND);
        assertThatThrownBy(() -> issueRequestService.get(UUID.randomUUID().toString(), owner.getId()))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ISSUE_REQUEST_NOT_FOUND);
    }

    @Test
    void 발행_실패로_취소된_요청의_메시지가_실제로는_기록돼_있었다면_발급하지_않고_PUBLISH_CANCELLED() throws Exception {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        Instant now = Instant.now();
        CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(UUID.randomUUID().toString(), coupon.getId(), user.getId(), now);
        assertThat(redisStock.tryIssue(coupon.getId(), user.getId(), now)).isEqualTo(RedisIssueResult.SUCCESS);   // 재고 4
        // 요청 경로: ack 타임아웃 → 실패로 판단 → 보상 + 503 (AsyncCouponIssueRequester.publishFailed 와 같은 호출)
        redisStock.restore(coupon.getId(), user.getId(), true, event.requestId());                                 // 재고 5

        publish(event);   // 그런데 메시지는 브로커에 실제로 기록돼 있었다

        await().atMost(WAIT).until(() -> issueRequestRepository.existsById(event.requestId()));
        IssueRequest result = issueRequestRepository.findById(event.requestId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        assertThat(result.getFailureReason()).isEqualTo("PUBLISH_CANCELLED");
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).as("503 을 받은 사용자에게 발급하지 않는다").isZero();
        assertThat(redisStock.getStock(coupon.getId())).as("재고가 두 번 풀리지 않는다").contains(5);
    }

    @Test
    void 같은_요청을_발행_실패_보상과_DLT_양쪽에서_복구해도_재고는_한_번만_늘어난다() {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        Instant now = Instant.now();
        CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(UUID.randomUUID().toString(), coupon.getId(), user.getId(), now);
        redisStock.tryIssue(coupon.getId(), user.getId(), now);                        // 재고 4
        redisStock.restore(coupon.getId(), user.getId(), true, event.requestId());     // 발행 실패 보상 → 5

        processor.failAfterRetriesExhausted(event);   // DLT 경로로도 도착
        processor.failAfterRetriesExhausted(event);   // DLT 메시지 재전송

        assertThat(redisStock.getStock(coupon.getId())).as("이중 복구 없음").contains(5);
        assertThat(issueRequestRepository.findById(event.requestId()).orElseThrow().getFailureReason()).isEqualTo("RETRY_EXHAUSTED");
    }

    private void publish(CouponIssueRequestedEvent event) throws Exception {
        kafkaTemplate.send(CouponIssueKafkaConfig.TOPIC, String.valueOf(event.eventId()), objectMapper.writeValueAsString(event)).get();
    }

    private Coupon openCoupon(int total) {
        return ConcurrencyTestSupport.saveOpenCoupon(couponRepository, redisStock, total);
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "async-" + UUID.randomUUID(), "사용자", null));
    }
}

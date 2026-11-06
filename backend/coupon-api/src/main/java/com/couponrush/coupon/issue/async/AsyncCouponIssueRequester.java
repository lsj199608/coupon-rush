package com.couponrush.coupon.issue.async;

import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.issue.redis.RedisIssueRejections;
import com.couponrush.coupon.issue.redis.RedisIssueResult;
import com.couponrush.coupon.request.PendingIssueRequestStore;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * [비동기 발급 — 요청 경로] Redis 판정 → (SUCCESS) PENDING 기록 → Kafka 발행 → requestId 반환.
 * 요청 경로에서 DB 를 쓰지 않는다. DB 저장은 CouponIssueEventConsumer 가 한다.
 *
 * 브로커 ack(acks=all)를 받은 뒤에 응답한다: 202 는 "요청을 잃어버리지 않는다"는 약속이라,
 * 발행이 확정되지 않았으면 Redis 를 되돌리고 503 으로 실패시킨다.
 */
@Component
public class AsyncCouponIssueRequester {

    private static final Logger log = LoggerFactory.getLogger(AsyncCouponIssueRequester.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final RedisCouponStock stock;
    private final RedisIssueRejections rejections;
    private final PendingIssueRequestStore pendingStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AsyncCouponIssueRequester(RedisCouponStock stock, RedisIssueRejections rejections,
                                     PendingIssueRequestStore pendingStore, KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper, Clock clock) {
        this.stock = stock;
        this.rejections = rejections;
        this.pendingStore = pendingStore;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @return requestId (상태 조회: GET /api/issue-requests/{requestId}) */
    public String request(Long couponId, Long userId) {
        Instant now = clock.instant();

        RedisIssueResult result = stock.tryIssue(couponId, userId, now);
        if (result != RedisIssueResult.SUCCESS) {
            throw rejections.toException(result, couponId, now);   // 소진·중복·기간 외: Kafka 에 아무것도 안 보낸다
        }

        String requestId = UUID.randomUUID().toString();
        CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(requestId, couponId, userId, now);
        pendingStore.save(requestId, couponId, userId, now);   // 발행 전에 기록 → 컨슈머가 먼저 끝나도 조회 순서가 맞다

        try {
            kafkaTemplate.send(CouponIssueKafkaConfig.TOPIC, String.valueOf(couponId), toJson(event))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);   // 브로커 ack 대기
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailed(event, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw publishFailed(event, e);
        }
        return requestId;
    }

    /**
     * 발행 실패: 이 요청을 "취소"로 확정한다 — Redis 재고 복구 + Set 에서 제거 + 복구 기록(requestId), PENDING 삭제, 503.
     *
     * 타임아웃은 "실제로는 브로커에 기록됐을 수도" 있다. 그 메시지가 컨슈머에 도착하면 복구 기록(restored Set)을 보고
     * 발급하지 않고 FAILED(PUBLISH_CANCELLED) 로 끝낸다 → 사용자는 503 을 받았는데 쿠폰이 생기는 일, 재고가 두 번 풀리는 일을 막는다.
     * 단, 컨슈머가 이 보상보다 "먼저" 처리해 버리면 막지 못한다 (docs/concurrency-notes.md 6장 한계).
     */
    private BusinessException publishFailed(CouponIssueRequestedEvent event, Exception cause) {
        log.warn("발급 이벤트 발행 실패 — 요청 취소, Redis 되돌림: requestId={}, couponId={}, userId={}",
                event.requestId(), event.eventId(), event.userId(), cause);
        try {
            stock.restore(event.eventId(), event.userId(), true, event.requestId());   // 멱등 (requestId)
        } catch (RuntimeException e) {
            // 재고가 덜 풀린 상태로 남는다 (안전한 방향). 대조 점검에서 "Redis 발급 > DB 발급"으로 드러난다
            log.error("발행 실패 후 Redis 보상 실패: requestId={} — 대조 점검으로 확인 필요", event.requestId(), e);
        }
        try {
            pendingStore.delete(event.requestId());
        } catch (RuntimeException e) {
            log.warn("PENDING 키 삭제 실패: requestId={} (TTL 로 정리됨)", event.requestId(), e);
        }
        return new BusinessException(ErrorCode.COUPON_ISSUE_UNAVAILABLE);
    }

    private String toJson(CouponIssueRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("발급 이벤트 직렬화 실패", e);
        }
    }
}

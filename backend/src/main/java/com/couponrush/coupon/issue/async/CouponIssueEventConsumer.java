package com.couponrush.coupon.issue.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 발급 이벤트 컨슈머 2개.
 *
 * 메인(coupon-issue-requests): 파티션 수(3)만큼 스레드. 키가 eventId 라서 같은 쿠폰은 한 스레드가 순서대로 처리한다.
 *   오프셋은 이 메서드가 정상 반환한 뒤에만 커밋된다(ack-mode=record) — 예외를 던지면 커밋하지 않고 재시도/DLT.
 *
 * DLT(coupon-issue-requests.DLT): 재시도를 모두 실패한 요청을 FAILED 로 확정하고 Redis 재고를 되돌린다.
 */
@Component
public class CouponIssueEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueEventConsumer.class);

    private final CouponIssueEventProcessor processor;
    private final ObjectMapper objectMapper;

    public CouponIssueEventConsumer(CouponIssueEventProcessor processor, ObjectMapper objectMapper) {
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    /**
     * concurrency(스레드 수)는 파티션 수 이하에서만 의미가 있다 (한 파티션은 한 스레드만 읽는다).
     * 키가 eventId 라서 이벤트 하나의 메시지는 모두 한 파티션 → 이벤트 하나의 처리 속도는 스레드를 늘려도 그대로다.
     * 동시에 열린 이벤트가 여러 개일 때 늘린 만큼 병렬로 처리된다 (측정: docs/concurrency-notes.md 7장).
     */
    @KafkaListener(id = CouponIssueKafkaConfig.MAIN_LISTENER_ID, topics = CouponIssueKafkaConfig.TOPIC,
            groupId = CouponIssueKafkaConfig.CONSUMER_GROUP, concurrency = "${app.kafka.consumer.concurrency:3}")
    public void consume(ConsumerRecord<String, String> record) {
        CouponIssueRequestedEvent event;
        try {
            event = objectMapper.readValue(record.value(), CouponIssueRequestedEvent.class);
        } catch (JsonProcessingException e) {
            // 재시도해도 소용없다 → 에러 핸들러가 재시도 없이 DLT 로 보낸다 (조사용으로 원본 보존)
            throw new InvalidCouponIssueMessageException(
                    "발급 이벤트 역직렬화 실패: partition=" + record.partition() + ", offset=" + record.offset(), e);
        }
        processor.process(event);
    }

    @KafkaListener(topics = CouponIssueKafkaConfig.DLT_TOPIC, groupId = CouponIssueKafkaConfig.DLT_CONSUMER_GROUP,
            containerFactory = CouponIssueKafkaConfig.DLT_CONTAINER_FACTORY)
    public void consumeDeadLetter(ConsumerRecord<String, String> record) {
        CouponIssueRequestedEvent event;
        try {
            event = objectMapper.readValue(record.value(), CouponIssueRequestedEvent.class);
        } catch (JsonProcessingException e) {
            // 형식이 깨진 메시지는 requestId 를 알 수 없어 FAILED 로 표시할 대상이 없다 → 원본은 DLT 에 남아 있으니 로그만
            log.error("DLT: 해석할 수 없는 메시지 — 수동 확인 필요: partition={}, offset={}", record.partition(), record.offset());
            return;
        }
        processor.failAfterRetriesExhausted(event);
    }
}

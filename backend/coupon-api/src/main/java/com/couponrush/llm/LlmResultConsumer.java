package com.couponrush.llm;

import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * llm-results 컨슈머. 전용 컨테이너(LlmKafkaConfig.RESULT_CONTAINER_FACTORY)와 컨슈머 그룹을 쓴다 — 발급 컨슈머와 스레드를 나누지 않는다.
 * 오프셋은 반영(DB 커밋)이 끝난 뒤 커밋된다 (ack-mode=record).
 */
@Component
public class LlmResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(LlmResultConsumer.class);

    private final LlmResultHandler handler;
    private final ObjectMapper objectMapper;

    public LlmResultConsumer(LlmResultHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = LlmKafkaConfig.RESULT_LISTENER_ID, topics = LlmTopics.RESULTS,
            groupId = LlmKafkaConfig.RESULT_CONSUMER_GROUP, containerFactory = LlmKafkaConfig.RESULT_CONTAINER_FACTORY)
    public void consume(ConsumerRecord<String, String> record) {
        LlmResultMessage message;
        try {
            message = objectMapper.readValue(record.value(), LlmResultMessage.class);
        } catch (JsonProcessingException e) {
            // 형식이 깨졌거나 계약 규칙 위반(예: FAILED 인데 사유 없음). 재시도해도 같다 → 건너뛰고, 그 요청은 마감 후 TIMEOUT
            log.error("LLM 결과 메시지를 읽을 수 없음 — 건너뜀: partition={}, offset={}, key={}",
                    record.partition(), record.offset(), record.key(), e);
            return;
        }
        handler.handle(message);
    }
}

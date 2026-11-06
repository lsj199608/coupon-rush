package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llmservice.LlmServiceProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * llm-requests 컨슈머. concurrency(기본 2) = 동시에 LLM 을 부르는 최대 수 → 비용·제공자 한도를 구조로 묶는다.
 * 결과 발행이 브로커에 확정된 뒤에만 반환한다 → 그 뒤에 오프셋 커밋 (ack-mode=record).
 * 발행이 실패하면 예외 → 재시도 → 처리 기록(llm:done)이 있으므로 LLM 은 다시 부르지 않고 발행만 다시 한다.
 */
@Component
public class LlmRequestConsumer {

    public static final String LISTENER_ID = "llmRequestListener";
    public static final String CONSUMER_GROUP = "llm-service";

    private final LlmRequestProcessor processor;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final LlmServiceProperties properties;

    public LlmRequestConsumer(LlmRequestProcessor processor, KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper, LlmServiceProperties properties) {
        this.processor = processor;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @KafkaListener(id = LISTENER_ID, topics = LlmTopics.REQUESTS, groupId = CONSUMER_GROUP,
            concurrency = "${app.llm.consumer.concurrency:2}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        LlmRequestMessage request;
        try {
            request = objectMapper.readValue(record.value(), LlmRequestMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidLlmRequestException(
                    "LLM 요청 역직렬화 실패: partition=" + record.partition() + ", offset=" + record.offset(), e);
        }
        processor.process(request).result().ifPresent(this::publish);
    }

    private void publish(LlmResultMessage result) {
        try {
            kafkaTemplate.send(LlmTopics.RESULTS, result.requestId().toString(), objectMapper.writeValueAsString(result))
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("결과 발행 중단: requestId=" + result.requestId(), e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("결과 발행 실패: requestId=" + result.requestId(), e);
        }
    }
}

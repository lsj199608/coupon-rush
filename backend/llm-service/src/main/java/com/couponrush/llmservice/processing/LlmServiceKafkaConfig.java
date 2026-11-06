package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.LlmTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 토픽과 에러 처리. 토픽은 coupon-api 도 같은 설정으로 선언한다 (어느 쪽이 먼저 떠도 된다).
 * <p>
 * 에러 처리 — LLM 호출 실패는 여기까지 오지 않는다 (FAILED 결과로 끝남). 여기 오는 것은:
 * <ul>
 *   <li>읽을 수 없는 메시지 → 재시도 없이 llm-requests.DLT (원본 + 예외 헤더 보존)</li>
 *   <li>Redis·Kafka 일시 장애(처리 기록, 결과 발행 실패) → 1s → 2s → 4s 재시도 후 DLT.
 *       재시도해도 llm:done 이 있으면 LLM 은 다시 부르지 않는다. DLT 로 간 요청은 coupon-api 에서 TIMEOUT 이 된다</li>
 * </ul>
 */
@Configuration
public class LlmServiceKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmServiceKafkaConfig.class);

    @Bean
    public NewTopic llmRequestsTopic(@Value("${app.kafka.topic.partitions:3}") int partitions,
                                     @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(LlmTopics.REQUESTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic llmResultsTopic(@Value("${app.kafka.topic.partitions:3}") int partitions,
                                    @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(LlmTopics.RESULTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic llmRequestsDltTopic(@Value("${app.kafka.topic.partitions:3}") int partitions,
                                        @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(LlmTopics.REQUESTS_DLT).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public CommonErrorHandler llmRequestErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(LlmTopics.REQUESTS_DLT, record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            dlt.accept(record, ex);   // 실패하면 예외 → 오프셋 유지
            log.error("LLM 요청 처리 포기 → DLT: partition={}, offset={}, key={}", record.partition(), record.offset(), record.key(), ex);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidLlmRequestException.class);
        return handler;
    }
}

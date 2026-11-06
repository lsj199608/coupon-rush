package com.couponrush.llm;

import com.couponrush.llm.contract.LlmTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * coupon-api 쪽 LLM Kafka 설정. 발급(CouponIssueKafkaConfig)과 토픽·컨슈머 그룹·리스너 컨테이너·에러 핸들러를 공유하지 않는다
 * → 결과 처리가 느려지거나 예외가 나도 발급 컨슈머 스레드는 영향을 받지 않는다.
 * <p>
 * 토픽은 키가 requestId 라 파티션 수가 성능에 거의 영향이 없다 (분당 몇 건). 발급 토픽과 같은 설정값을 쓰되 1개여도 된다.
 * llm-service 도 같은 토픽을 선언한다 (NewTopic 은 이미 있으면 아무것도 안 함 — 어느 쪽이 먼저 떠도 된다).
 */
@Configuration
public class LlmKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmKafkaConfig.class);

    public static final String RESULT_CONSUMER_GROUP = "coupon-api-llm-results";
    public static final String RESULT_LISTENER_ID = "llmResultListener";
    public static final String RESULT_CONTAINER_FACTORY = "llmResultContainerFactory";

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

    /**
     * 결과 컨슈머 전용 컨테이너 (스레드 1개 — 분당 몇 건).
     * DB 일시 장애는 1초 간격 5회 재시도 후 포기하고 로그만 남긴다 → 그 요청은 마감 후 TIMEOUT 이 된다.
     * 결과용 DLT 는 두지 않는다: 늦게 반영해 봐야 관리자는 이미 실패 안내를 봤고, 다시 생성하면 된다.
     * (에러 핸들러를 Bean 으로 만들면 발급 메인 핸들러가 "유일한 CommonErrorHandler Bean"이 아니게 되어 자동 적용이 깨진다 → 여기서 직접 생성)
     */
    @Bean(RESULT_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> llmResultContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, ex) -> log.error("LLM 결과 반영 포기 (마감 후 TIMEOUT 처리됨): partition={}, offset={}, key={}",
                        record.partition(), record.offset(), record.key(), ex),
                new FixedBackOff(1_000L, 5)));
        return factory;
    }
}

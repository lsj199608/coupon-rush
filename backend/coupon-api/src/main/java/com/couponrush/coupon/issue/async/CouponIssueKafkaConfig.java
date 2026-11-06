package com.couponrush.coupon.issue.async;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 토픽, 재시도, DLT 설정.
 *
 * coupon-issue-requests: 파티션 3, 메시지 키 = eventId(쿠폰 ID).
 *   같은 쿠폰의 요청은 항상 같은 파티션 → 한 컨슈머 스레드가 순서대로 처리한다 (쿠폰 행 락 경합 없음).
 * coupon-issue-requests.DLT: 재시도를 모두 실패한 메시지. 원본과 같은 파티션 번호로 보내므로 파티션 수를 맞춘다.
 * 복제 수는 로컬 단일 브로커라 1. 운영에서는 3 + min.insync.replicas=2 여야 acks=all 이 의미가 있다.
 *
 * 오프셋 커밋: ack-mode=record (application.yml). 리스너가 "정상 반환"한 뒤에만 커밋한다.
 *   리스너는 DB 트랜잭션 커밋이 끝난 뒤 반환하므로 = DB 저장 성공 후 커밋.
 *   실패하면 커밋하지 않고 같은 오프셋으로 되감아(seek) 재시도한다. DLT 발행에 성공한 경우에만 그 오프셋을 넘긴다.
 */
@Configuration
public class CouponIssueKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueKafkaConfig.class);

    public static final String TOPIC = "coupon-issue-requests";
    public static final String DLT_TOPIC = TOPIC + ".DLT";
    public static final String CONSUMER_GROUP = "coupon-issue-consumer";
    public static final String DLT_CONSUMER_GROUP = "coupon-issue-dlt-consumer";
    public static final String DLT_CONTAINER_FACTORY = "couponIssueDltContainerFactory";
    public static final String MAIN_LISTENER_ID = "couponIssueListener";

    @Bean
    public NewTopic couponIssueRequestsTopic(@Value("${app.kafka.topic.partitions:3}") int partitions,
                                             @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(TOPIC).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic couponIssueRequestsDltTopic(@Value("${app.kafka.topic.partitions:3}") int partitions,
                                                @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(DLT_TOPIC).partitions(partitions).replicas(replicas).build();   // 원본 파티션 번호로 보내므로 수를 맞춘다
    }

    /**
     * 메인 컨슈머 에러 처리 (Boot 가 기본 리스너 컨테이너에 자동으로 적용한다 — 이 타입의 Bean 은 이것 하나여야 한다).
     * - 일시 오류(DB 연결 끊김 등): 지수 백오프로 재시도. 재시도 중에는 같은 파티션의 다음 메시지도 기다린다 (쿠폰별 순서 유지).
     * - 재시도 소진: DLT 로 발행 (원본 메시지 + 예외 정보 헤더). 발행이 실패하면 오프셋을 넘기지 않고 다시 처리한다.
     * - 형식이 깨진 메시지: 재시도 없이 바로 DLT.
     * - 비즈니스 실패(회원 없음, 중복 등)는 프로세서가 FAILED 로 처리하므로 여기까지 오지 않는다.
     */
    @Bean
    public CommonErrorHandler couponIssueErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      CouponIssueConsumerProperties properties,
                                                      CouponIssueConsumerMetrics metrics) {
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(DLT_TOPIC, record.partition()));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        backOff.setInitialInterval(properties.initialInterval().toMillis());
        backOff.setMultiplier(properties.multiplier());
        backOff.setMaxInterval(properties.maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            dltPublisher.accept(record, ex);   // 실패하면 예외 → 오프셋 유지, 다시 처리
            metrics.publishedToDlt();
            log.error("재시도 소진 → DLT 발행: partition={}, offset={}, key={}", record.partition(), record.offset(), record.key(), ex);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidCouponIssueMessageException.class);
        // 실패할 때마다 호출된다 (최초 시도·마지막 시도 포함, 재시도하지 않는 예외도 1번)
        handler.setRetryListeners((record, ex, deliveryAttempt) -> {
            metrics.failedDelivery();
            log.warn("발급 이벤트 처리 실패: attempt={}/{}, partition={}, offset={}, cause={}",
                    deliveryAttempt, properties.maxRetries() + 1, record.partition(), record.offset(),
                    rootCauseMessage(ex));
        });
        return handler;
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * DLT 컨슈머 전용 컨테이너. DLT 처리(FAILED 기록 + 보상)는 DB 가 돌아올 때까지 2초 간격으로 계속 재시도한다.
     * DLT 의 DLT 는 만들지 않는다 — 여기서 실패하는 원인은 DB 장애뿐이고, 기다리면 해결된다.
     * (에러 핸들러를 Bean 으로 등록하면 위의 메인 핸들러가 "유일한 Bean"이 아니게 되어 자동 적용이 깨지므로 여기서 직접 만든다)
     */
    @Bean(DLT_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> couponIssueDltContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}

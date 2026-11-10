package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmResultStatus;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llmservice.TestcontainersConfiguration;
import com.couponrush.llmservice.client.FakeLlmClient;
import com.couponrush.llmservice.client.LlmCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * llm-service 단독 흐름 (실제 Kafka·Redis, fake 제공자). coupon-api 없이 검증한다:
 * 테스트가 coupon-api 대신 llm-requests 를 발행하고, llm-results 를 직접 읽는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LlmRequestFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoSpyBean FakeLlmClient llmClient;   // 실제 fake 로 동작하면서 호출 횟수를 센다

    Consumer<String, String> results;
    Consumer<String, String> deadLetters;

    @BeforeEach
    void setUp() {
        results = consumerFactory.createConsumer("test-results-" + UUID.randomUUID(), null);
        results.subscribe(List.of(LlmTopics.RESULTS));
        deadLetters = consumerFactory.createConsumer("test-dlt-" + UUID.randomUUID(), null);
        deadLetters.subscribe(List.of(LlmTopics.REQUESTS_DLT));
    }

    @AfterEach
    void tearDown() {
        results.close();
        deadLetters.close();
        redisTemplate.delete(redisTemplate.keys("llm:*"));
    }

    private static LlmRequestMessage request(Instant deadline) {
        Instant requestedAt = deadline.minusSeconds(60);
        return LlmRequestMessage.eventCopy(UUID.randomUUID(), 7L, requestedAt, deadline, false,
                new EventCopyInput("겨울 한정 핫초코 50% 할인", EventCopyTone.URGENT, 300, null, null));
    }

    private static LlmRequestMessage freshRequest() {
        return request(Instant.now().plusSeconds(60));
    }

    private void send(LlmRequestMessage message) throws Exception {
        send(message.requestId().toString(), objectMapper.writeValueAsString(message));
    }

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send(LlmTopics.REQUESTS, key, value).get();
    }

    /** 이 요청의 결과를 기다린다 (다른 테스트의 결과는 키로 거른다) */
    private List<LlmResultMessage> awaitResults(UUID requestId, int count) {
        List<LlmResultMessage> found = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(results, Duration.ofMillis(300))) {
                if (requestId.toString().equals(record.key())) {
                    found.add(objectMapper.readValue(record.value(), LlmResultMessage.class));
                }
            }
            return found.size() >= count;
        });
        return found;
    }

    @Test
    void 요청을_받으면_fake_로_생성해_결과를_보낸다() throws Exception {
        LlmRequestMessage request = freshRequest();

        send(request);

        LlmResultMessage result = awaitResults(request.requestId(), 1).get(0);
        assertThat(result.status()).isEqualTo(LlmResultStatus.COMPLETED);
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates().get(0).name()).contains("핫초코");
        assertThat(result.model()).isEqualTo("fake");
        assertThat(result.totalTokens()).isPositive();
        assertThat(result.cached()).isFalse();
        verify(llmClient, times(1)).generateEventCopy(any());
    }

    @Test
    void 같은_요청이_다시_와도_LLM_은_한_번만_부르고_같은_결과를_다시_보낸다() throws Exception {
        LlmRequestMessage request = freshRequest();

        send(request);
        awaitResults(request.requestId(), 1);
        send(request);   // 재전달 (오프셋 커밋 전 장애, 리밸런스 등)

        List<LlmResultMessage> all = new ArrayList<>(awaitResults(request.requestId(), 1));
        assertThat(all.get(0).status()).isEqualTo(LlmResultStatus.COMPLETED);
        verify(llmClient, times(1)).generateEventCopy(any());
        assertThat(redisTemplate.hasKey(ProcessedRequestStore.doneKey(request.requestId()))).isTrue();
        assertThat(redisTemplate.getExpire(ProcessedRequestStore.doneKey(request.requestId())))
                .isBetween(1L, Duration.ofDays(1).toSeconds());
        assertThat(redisTemplate.hasKey(ProcessedRequestStore.inflightKey(request.requestId())))
                .as("처리가 끝나면 처리 중 표시는 지운다").isFalse();
    }

    @Test
    void 마감이_지난_요청은_LLM_을_부르지_않고_결과도_보내지_않는다() throws Exception {
        LlmRequestMessage expired = request(Instant.now().minusSeconds(1));
        LlmRequestMessage marker = freshRequest();

        // 같은 키(=같은 파티션)로 "만료 → 표시용" 순서로 보낸다. 표시용 결과가 오면 만료 요청은 이미 처리(건너뜀)된 것
        send("same-key", objectMapper.writeValueAsString(expired));
        send("same-key", objectMapper.writeValueAsString(marker));
        awaitResults(marker.requestId(), 1);

        assertThat(KafkaTestUtils.getRecords(results, Duration.ofMillis(500)).records(LlmTopics.RESULTS))
                .noneMatch(r -> r.value().contains(expired.requestId().toString()));
        verify(llmClient, times(1)).generateEventCopy(any());   // 표시용 1번뿐
        assertThat(redisTemplate.hasKey(ProcessedRequestStore.doneKey(expired.requestId()))).isFalse();
    }

    @Test
    void LLM_실패는_재시도하지_않고_FAILED_결과로_보낸다() throws Exception {
        doThrow(new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "제공자 503"))
                .when(llmClient).generateEventCopy(any());
        LlmRequestMessage request = freshRequest();

        send(request);

        LlmResultMessage result = awaitResults(request.requestId(), 1).get(0);
        assertThat(result.status()).isEqualTo(LlmResultStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE);
        assertThat(result.candidates()).isEmpty();
        verify(llmClient, times(1)).generateEventCopy(any());   // Kafka 재시도로 다시 부르지 않는다
    }

    @Test
    void 예상하지_못한_예외도_FAILED_로_끝내고_반복_호출하지_않는다() throws Exception {
        doThrow(new IllegalStateException("버그")).when(llmClient).generateEventCopy(any());
        LlmRequestMessage request = freshRequest();

        send(request);

        LlmResultMessage result = awaitResults(request.requestId(), 1).get(0);
        assertThat(result.failureReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE);
        verify(llmClient, times(1)).generateEventCopy(any());
    }

    @Test
    void 읽을_수_없는_요청은_재시도_없이_DLT_로_보내고_LLM_을_부르지_않는다() throws Exception {
        String key = "broken-" + UUID.randomUUID();

        send(key, "{not json");

        ConsumerRecord<String, String> dead = await().atMost(WAIT).until(() -> {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(deadLetters, Duration.ofMillis(300))) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
            return null;
        }, r -> r != null);
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        verify(llmClient, never()).generateEventCopy(any());
    }

    @Test
    void 모르는_스키마_버전은_처리하지_않는다() throws Exception {
        LlmRequestMessage future = new LlmRequestMessage(99, UUID.randomUUID(),
                com.couponrush.llm.contract.LlmRequestType.EVENT_COPY, 7L, Instant.now(), Instant.now().plusSeconds(60),
                false, new EventCopyInput("미래", EventCopyTone.FRIENDLY, 1, null, null), null);
        LlmRequestMessage marker = freshRequest();

        send("same-key-2", objectMapper.writeValueAsString(future));
        send("same-key-2", objectMapper.writeValueAsString(marker));
        awaitResults(marker.requestId(), 1);

        verify(llmClient, times(1)).generateEventCopy(any());
        assertThat(redisTemplate.hasKey(ProcessedRequestStore.doneKey(future.requestId()))).isFalse();
    }

    @Test
    void 다른_작업자가_처리_중이면_건너뛴다() throws Exception {
        LlmRequestMessage request = freshRequest();
        redisTemplate.opsForValue().set(ProcessedRequestStore.inflightKey(request.requestId()), "1", Duration.ofMinutes(1));
        LlmRequestMessage marker = freshRequest();

        send("same-key-3", objectMapper.writeValueAsString(request));
        send("same-key-3", objectMapper.writeValueAsString(marker));
        awaitResults(marker.requestId(), 1);

        verify(llmClient, times(1)).generateEventCopy(any());   // 표시용만
    }
}

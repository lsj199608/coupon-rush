package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmResultStatus;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llm.contract.LlmUsageKeys;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 비용 통제: 응답 캐시 + 일일 토큰 예산 + 사용량 기록 (실제 Kafka·Redis, fake 제공자 — 토큰은 글자 수로 흉내) */
@SpringBootTest   // 예산은 기본값 50,000 그대로 (LlmRequestFlowTest 와 컨텍스트·컨테이너 공유)
@Import(TestcontainersConfiguration.class)
class CostControlFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoSpyBean FakeLlmClient llmClient;

    Consumer<String, String> results;

    @BeforeEach
    void setUp() {
        results = consumerFactory.createConsumer("test-cost-" + UUID.randomUUID(), null);
        results.subscribe(List.of(LlmTopics.RESULTS));
    }

    @AfterEach
    void tearDown() {
        results.close();
        redisTemplate.delete(redisTemplate.keys("llm:*"));
    }

    private static LlmRequestMessage request(String keywords, boolean regenerate) {
        Instant now = Instant.now();
        return LlmRequestMessage.eventCopy(UUID.randomUUID(), 7L, now, now.plusSeconds(60), regenerate,
                new EventCopyInput(keywords, EventCopyTone.FRIENDLY, 100, null, null));
    }

    private LlmResultMessage process(LlmRequestMessage request) throws Exception {
        kafkaTemplate.send(LlmTopics.REQUESTS, request.requestId().toString(), objectMapper.writeValueAsString(request)).get();
        return await().atMost(WAIT).until(() -> {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(results, Duration.ofMillis(300))) {
                if (request.requestId().toString().equals(r.key())) {
                    return objectMapper.readValue(r.value(), LlmResultMessage.class);
                }
            }
            return null;
        }, r -> r != null);
    }

    private Map<Object, Object> usage() {
        return redisTemplate.opsForHash().entries(LlmUsageKeys.usageKey(Instant.now()));
    }

    private long usageField(String field) {
        Object value = usage().get(field);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    // ───────────────────────── 캐시 ─────────────────────────

    @Test
    void 같은_입력이면_두_번째는_캐시로_답하고_LLM_을_부르지_않는다() throws Exception {
        LlmResultMessage first = process(request("봄맞이 할인", false));
        LlmResultMessage second = process(request("  봄맞이   할인 ", false));   // 공백만 다른 같은 입력

        assertThat(first.cached()).isFalse();
        assertThat(second.status()).isEqualTo(LlmResultStatus.COMPLETED);
        assertThat(second.cached()).isTrue();
        assertThat(second.candidates()).isEqualTo(first.candidates());
        assertThat(second.totalTokens()).as("캐시 응답은 비용 0").isZero();
        verify(llmClient, times(1)).generateEventCopy(any());
        assertThat(usageField(LlmUsageKeys.CACHE_HITS)).isEqualTo(1);
        assertThat(usageField(LlmUsageKeys.CALLS)).isEqualTo(1);
    }

    @Test
    void 검색도_같은_날_같은_질문이면_캐시로_답하고_문구_캐시와_섞이지_않는다() throws Exception {
        Instant now = Instant.now();
        LlmResultMessage first = process(LlmRequestMessage.eventSearch(UUID.randomUUID(), 7L, now, now.plusSeconds(60),
                new EventSearchInput("커피 쿠폰")));
        LlmResultMessage second = process(LlmRequestMessage.eventSearch(UUID.randomUUID(), 7L, now, now.plusSeconds(60),
                new EventSearchInput("  커피   쿠폰 ")));
        LlmResultMessage copy = process(request("커피 쿠폰", false));   // 같은 글자의 문구 생성 요청

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.searchFilter()).isEqualTo(first.searchFilter());
        assertThat(second.totalTokens()).isZero();
        verify(llmClient, times(1)).extractSearchFilter(any(), any());
        assertThat(copy.cached()).as("검색 캐시를 문구 결과로 쓰지 않는다").isFalse();
        assertThat(copy.candidates()).hasSize(3);
    }

    @Test
    void 다시_생성은_캐시를_건너뛰고_새로_부른다() throws Exception {
        process(request("봄맞이 할인", false));

        LlmResultMessage regenerated = process(request("봄맞이 할인", true));

        assertThat(regenerated.cached()).isFalse();
        verify(llmClient, times(2)).generateEventCopy(any());
    }

    @Test
    void 입력이_다르면_캐시를_쓰지_않는다() throws Exception {
        process(request("봄맞이 할인", false));
        process(request("여름 할인", false));

        verify(llmClient, times(2)).generateEventCopy(any());
    }

    @Test
    void 실패한_결과는_캐시하지_않는다() throws Exception {
        doThrow(new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "일시 장애"))
                .doCallRealMethod()
                .when(llmClient).generateEventCopy(any());

        assertThat(process(request("봄맞이 할인", false)).status()).isEqualTo(LlmResultStatus.FAILED);
        LlmResultMessage retried = process(request("봄맞이 할인", false));

        assertThat(retried.status()).isEqualTo(LlmResultStatus.COMPLETED);
        assertThat(retried.cached()).as("실패는 캐시되지 않았으므로 새로 생성").isFalse();
    }

    // ───────────────────────── 예산·사용량 ─────────────────────────

    @Test
    void 호출할_때마다_실제_사용_토큰을_Redis_에_기록한다() throws Exception {
        LlmResultMessage a = process(request("봄맞이 할인", false));
        LlmResultMessage b = process(request("여름 할인", false));

        assertThat(usageField(LlmUsageKeys.TOTAL_TOKENS)).isEqualTo(a.totalTokens() + b.totalTokens());
        assertThat(usageField(LlmUsageKeys.INPUT_TOKENS)).isEqualTo(a.inputTokens() + b.inputTokens());
        assertThat(usageField(LlmUsageKeys.OUTPUT_TOKENS)).isEqualTo(a.outputTokens() + b.outputTokens());
        assertThat(usageField(LlmUsageKeys.CALLS)).isEqualTo(2);
        assertThat(redisTemplate.getExpire(LlmUsageKeys.usageKey(Instant.now())))
                .as("사용량 기록은 8일 보관").isBetween(Duration.ofDays(7).toSeconds(), Duration.ofDays(8).toSeconds());
    }

    @Test
    void 실패해도_쓴_토큰은_예산에_잡힌다() throws Exception {
        doThrow(new LlmCallException(LlmFailureReason.INVALID_OUTPUT, "형식 위반", "fake", 300, 500, null))
                .when(llmClient).generateEventCopy(any());

        LlmResultMessage failed = process(request("봄맞이 할인", false));

        assertThat(failed.failureReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT);
        assertThat(usageField(LlmUsageKeys.TOTAL_TOKENS)).isEqualTo(800);
    }

    @Test
    void 예산을_다_쓰면_LLM_을_부르지_않고_BUDGET_EXCEEDED() throws Exception {
        redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "50000");

        LlmResultMessage result = process(request("봄맞이 할인", false));

        assertThat(result.status()).isEqualTo(LlmResultStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(LlmFailureReason.BUDGET_EXCEEDED);
        verify(llmClient, never()).generateEventCopy(any());
        assertThat(usageField(LlmUsageKeys.BUDGET_REJECTIONS)).isEqualTo(1);
        assertThat(usageField(LlmUsageKeys.TOTAL_TOKENS)).as("거절은 토큰을 쓰지 않는다").isEqualTo(50_000);
    }

    @Test
    void 예산이_조금이라도_남아_있으면_호출한다() throws Exception {
        redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "49999");

        assertThat(process(request("봄맞이 할인", false)).status()).isEqualTo(LlmResultStatus.COMPLETED);
        assertThat(usageField(LlmUsageKeys.TOTAL_TOKENS))
                .as("동시 처리 수만큼 예산을 살짝 넘을 수 있다 (호출 전 확인, 호출 후 기록)").isGreaterThan(50_000);
    }

    @Test
    void 예산을_다_써도_캐시된_응답은_준다() throws Exception {
        process(request("봄맞이 할인", false));
        redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "50000");

        LlmResultMessage cached = process(request("봄맞이 할인", false));

        assertThat(cached.status()).isEqualTo(LlmResultStatus.COMPLETED);
        assertThat(cached.cached()).isTrue();
        verify(llmClient, times(1)).generateEventCopy(any());
    }
}

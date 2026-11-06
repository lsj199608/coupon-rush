package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmResultStatus;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.LlmClient;
import com.couponrush.llmservice.client.LlmCompletion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 요청 하나를 처리해 결과 메시지를 만든다 (발행은 컨슈머가). 순서:
 * <ol>
 *   <li>모르는 스키마 버전 → 건너뜀 (결과 없음 → coupon-api 가 마감 후 TIMEOUT)</li>
 *   <li>이미 처리한 요청 → 저장해 둔 결과를 그대로 (LLM 호출 0회)</li>
 *   <li>마감이 지났음 → 건너뜀. 기다리는 사람이 없는데 비용을 쓰지 않는다 (llm-service 가 밀렸다 복구된 경우 등)</li>
 *   <li>다른 스레드가 처리 중 → 건너뜀</li>
 *   <li>LLM 호출 → COMPLETED / FAILED 결과를 Redis 에 저장한 뒤 반환 (발행 실패로 재처리돼도 2번에서 끝난다)</li>
 * </ol>
 * LLM 실패는 예외로 올리지 않고 FAILED 결과로 바꾼다 — 예외로 두면 Kafka 가 재시도하면서 LLM 을 또 부른다.
 */
@Component
public class LlmRequestProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestProcessor.class);
    private static final Duration INFLIGHT_MARGIN = Duration.ofSeconds(60);

    /** 처리 결과 종류 (테스트·로그용) */
    public enum Outcome {
        GENERATED, CACHED, BUDGET_REJECTED, REPLAYED, SKIPPED_EXPIRED, SKIPPED_UNSUPPORTED_VERSION, SKIPPED_IN_PROGRESS
    }

    public record Processed(Outcome outcome, Optional<LlmResultMessage> result) {
        static Processed skipped(Outcome outcome) {
            return new Processed(outcome, Optional.empty());
        }
    }

    private final LlmClient client;
    private final ProcessedRequestStore store;
    private final ResponseCache cache;
    private final UsageLedger usage;
    private final ObjectMapper objectMapper;
    private final LlmServiceProperties properties;
    private final Clock clock;

    public LlmRequestProcessor(LlmClient client, ProcessedRequestStore store, ResponseCache cache, UsageLedger usage,
                               ObjectMapper objectMapper, LlmServiceProperties properties, Clock clock) {
        this.client = client;
        this.store = store;
        this.cache = cache;
        this.usage = usage;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public Processed process(LlmRequestMessage request) {
        if (!LlmTopics.isSupported(request.schemaVersion())) {
            log.warn("지원하지 않는 요청 버전 — 건너뜀: requestId={}, schemaVersion={}", request.requestId(), request.schemaVersion());
            return Processed.skipped(Outcome.SKIPPED_UNSUPPORTED_VERSION);
        }

        Optional<String> done = store.findResult(request.requestId());
        if (done.isPresent()) {
            log.info("이미 처리한 요청 — 결과만 다시 보냄: requestId={}", request.requestId());
            return new Processed(Outcome.REPLAYED, Optional.of(fromJson(done.get())));
        }

        Instant now = clock.instant();
        if (request.isExpired(now)) {
            log.info("마감이 지난 요청 — LLM 호출 안 함: requestId={}, deadline={}", request.requestId(), request.deadline());
            return Processed.skipped(Outcome.SKIPPED_EXPIRED);
        }

        if (!store.tryMarkInflight(request.requestId(), Duration.between(now, request.deadline()).plus(INFLIGHT_MARGIN))) {
            log.warn("다른 작업자가 처리 중 — 건너뜀: requestId={}", request.requestId());
            return Processed.skipped(Outcome.SKIPPED_IN_PROGRESS);
        }
        try {
            Processed processed = cachedOrBudgetedOrGenerated(request, now);
            processed.result().ifPresent(result ->
                    store.saveResult(request.requestId(), toJson(result), properties.resultRetention()));
            return processed;
        } finally {
            store.clearInflight(request.requestId());
        }
    }

    /**
     * ⑤ 응답 캐시 (regenerate 가 아니면) → ⑥ 일일 예산 → ⑦ LLM 호출 → 사용량 기록 → 성공이면 캐시 저장.
     * 캐시를 예산보다 먼저 보는 이유: 캐시 응답은 비용이 0이라 예산을 다 써도 줄 수 있다.
     */
    private Processed cachedOrBudgetedOrGenerated(LlmRequestMessage request, Instant now) {
        String cacheKey = ResponseCache.key(client.cacheIdentity(), request.eventCopy());

        if (!request.regenerate()) {
            Optional<ResponseCache.Entry> cached = cache.find(cacheKey);
            if (cached.isPresent()) {
                usage.recordCacheHit(now);
                log.info("응답 캐시 사용 — LLM 호출 안 함: requestId={}", request.requestId());
                return new Processed(Outcome.CACHED, Optional.of(LlmResultMessage.completed(request.requestId(),
                        cached.get().candidates(), cached.get().model(), 0, 0, true, elapsedMillis(now), clock.instant())));
            }
        }

        if (!usage.hasRemaining(now)) {
            usage.recordBudgetRejection(now);
            log.warn("일일 토큰 예산 소진 — LLM 호출 안 함: requestId={}, used={}/{}",
                    request.requestId(), usage.usedTokens(now), usage.dailyBudget());
            return new Processed(Outcome.BUDGET_REJECTED, Optional.of(LlmResultMessage.failed(request.requestId(),
                    LlmFailureReason.BUDGET_EXCEEDED, null, 0, 0, elapsedMillis(now), clock.instant())));
        }

        LlmResultMessage result = generate(request, now);
        if (result.totalTokens() > 0) {
            usage.recordCall(now, result.inputTokens(), result.outputTokens());
        }
        if (result.status() == LlmResultStatus.COMPLETED) {
            cache.save(cacheKey, new ResponseCache.Entry(result.candidates(), result.model()));
        }
        return new Processed(Outcome.GENERATED, Optional.of(result));
    }

    private LlmResultMessage generate(LlmRequestMessage request, Instant startedAt) {
        try {
            LlmCompletion completion = switch (request.type()) {
                case EVENT_COPY -> client.generateEventCopy(request.eventCopy());
            };
            return LlmResultMessage.completed(request.requestId(), completion.candidates(), completion.model(),
                    completion.inputTokens(), completion.outputTokens(), false, elapsedMillis(startedAt), clock.instant());
        } catch (LlmCallException e) {
            log.warn("LLM 호출 실패: requestId={}, reason={}, message={}", request.requestId(), e.getReason(), e.getMessage());
            return LlmResultMessage.failed(request.requestId(), e.getReason(), e.getModel(),
                    e.getInputTokens(), e.getOutputTokens(), elapsedMillis(startedAt), clock.instant());
        } catch (RuntimeException e) {
            // 예상 못 한 오류(버그 포함)도 FAILED 로 끝낸다 — 예외를 올리면 Kafka 재시도로 LLM 을 반복 호출하게 된다
            log.error("LLM 호출 중 예상하지 못한 오류: requestId={}", request.requestId(), e);
            return LlmResultMessage.failed(request.requestId(), LlmFailureReason.LLM_UNAVAILABLE, null, 0, 0,
                    elapsedMillis(startedAt), clock.instant());
        }
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
    }

    private String toJson(LlmResultMessage result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결과 직렬화 실패: requestId=" + result.requestId(), e);
        }
    }

    private LlmResultMessage fromJson(String json) {
        try {
            return objectMapper.readValue(json, LlmResultMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 결과를 읽을 수 없음", e);
        }
    }
}

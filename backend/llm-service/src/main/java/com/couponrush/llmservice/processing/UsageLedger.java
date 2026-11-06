package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.LlmUsageKeys;
import com.couponrush.llmservice.LlmServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 일일 토큰 예산과 사용량 기록 (Redis hash {@code llm:usage:{yyyyMMdd}}, 한국 날짜, TTL 8일).
 * 필드: total_tokens(예산 기준) / input_tokens / output_tokens / calls / cache_hits / budget_rejections.
 * <p>
 * 예산 판정은 "호출 전 total_tokens &lt; 예산" — 실제 사용량은 호출이 끝나야 알 수 있으므로 호출 뒤에 더한다.
 * 동시에 처리 중인 요청 수(consumer concurrency, 기본 2)만큼 예산을 살짝 넘을 수 있다 (LlmServiceProperties.Budget 참고).
 * <p>
 * Redis 장애 시: 예산 확인이 실패하면 호출하지 않는다 (비용 상한이 깨지는 쪽보다 생성 실패가 안전) → 예외로 올려 Kafka 재시도.
 * 사용량 기록 실패는 로그만 남긴다 (이미 결과를 만들었으므로 버리지 않는다).
 */
@Component
public class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);
    private static final Duration RETENTION = Duration.ofDays(8);

    private final StringRedisTemplate redisTemplate;
    private final LlmServiceProperties.Budget budget;

    public UsageLedger(StringRedisTemplate redisTemplate, LlmServiceProperties properties) {
        this.redisTemplate = redisTemplate;
        this.budget = properties.budget();
    }

    public long dailyBudget() {
        return budget.dailyTokens();
    }

    public long usedTokens(Instant now) {
        Object value = hash().get(LlmUsageKeys.usageKey(now), LlmUsageKeys.TOTAL_TOKENS);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    public boolean hasRemaining(Instant now) {
        return usedTokens(now) < budget.dailyTokens();
    }

    /** LLM 을 실제로 부른 요청 1건의 사용량 (성공·실패 모두 — 비용은 이미 나갔다) */
    public void recordCall(Instant now, int inputTokens, int outputTokens) {
        try {
            String key = LlmUsageKeys.usageKey(now);
            long total = hash().increment(key, LlmUsageKeys.TOTAL_TOKENS, (long) inputTokens + outputTokens);
            hash().increment(key, LlmUsageKeys.INPUT_TOKENS, inputTokens);
            hash().increment(key, LlmUsageKeys.OUTPUT_TOKENS, outputTokens);
            hash().increment(key, LlmUsageKeys.CALLS, 1);
            expire(key);
            if (total >= budget.dailyTokens()) {
                log.warn("일일 LLM 토큰 예산 소진: {}/{} — 오늘 남은 요청은 거절됨", total, budget.dailyTokens());
            }
        } catch (RuntimeException e) {
            log.error("LLM 사용량 기록 실패 (예산 집계가 실제보다 적을 수 있음): in={}, out={}", inputTokens, outputTokens, e);
        }
    }

    public void recordCacheHit(Instant now) {
        incrementQuietly(now, LlmUsageKeys.CACHE_HITS);
    }

    public void recordBudgetRejection(Instant now) {
        incrementQuietly(now, LlmUsageKeys.BUDGET_REJECTIONS);
    }

    private void incrementQuietly(Instant now, String field) {
        try {
            String key = LlmUsageKeys.usageKey(now);
            hash().increment(key, field, 1);
            expire(key);
        } catch (RuntimeException e) {
            log.warn("LLM 사용량 통계 기록 실패: field={}", field, e);
        }
    }

    private void expire(String key) {
        redisTemplate.expire(key, RETENTION);
    }

    private HashOperations<String, Object, Object> hash() {
        return redisTemplate.opsForHash();
    }
}

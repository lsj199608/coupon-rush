package com.couponrush.llmservice.processing;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 요청별 처리 기록 (Redis) — 같은 메시지가 다시 와도 LLM 을 두 번 부르지 않기 위해 (비용).
 * <ul>
 *   <li>{@code llm:done:{requestId}} — 만든 결과 메시지(JSON). 있으면 다시 발행만 한다 (결과 발행 실패 후 재처리, 재전달). TTL 1일</li>
 *   <li>{@code llm:inflight:{requestId}} — 처리 중 표시 (SET NX). 리밸런스로 같은 메시지를 두 스레드가 동시에 잡았을 때 한쪽만 호출. TTL = 남은 마감 + 여유</li>
 * </ul>
 * Kafka 오프셋만으로는 막을 수 없다: 결과 발행 후 오프셋 커밋 전에 죽으면 같은 메시지가 다시 온다 (at-least-once).
 */
@Component
public class ProcessedRequestStore {

    private final StringRedisTemplate redisTemplate;

    public ProcessedRequestStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String doneKey(UUID requestId) {
        return "llm:done:" + requestId;
    }

    public static String inflightKey(UUID requestId) {
        return "llm:inflight:" + requestId;
    }

    public Optional<String> findResult(UUID requestId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(doneKey(requestId)));
    }

    public void saveResult(UUID requestId, String resultJson, Duration retention) {
        redisTemplate.opsForValue().set(doneKey(requestId), resultJson, retention);
    }

    /** @return false 면 다른 스레드(또는 인스턴스)가 처리 중 */
    public boolean tryMarkInflight(UUID requestId, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(inflightKey(requestId), "1", ttl));
    }

    public void clearInflight(UUID requestId) {
        redisTemplate.delete(inflightKey(requestId));
    }
}

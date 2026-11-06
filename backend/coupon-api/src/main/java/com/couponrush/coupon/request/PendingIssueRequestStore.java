package com.couponrush.coupon.request;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * PENDING 상태의 발급 요청 (Redis). issue-request:{requestId} → hash(couponId, userId, requestedAt), TTL 1일.
 * 요청 경로에서 DB 를 쓰지 않으려고 PENDING 은 Redis 에만 둔다. 최종 결과는 컨슈머가 DB(issue_requests)에 기록한다.
 * TTL 이 지나도 DB 에 결과가 없으면 "처리되지 못한 요청"이다 (대조/복구 대상 — docs/concurrency-notes.md 5장).
 */
@Component
public class PendingIssueRequestStore {

    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;

    public PendingIssueRequestStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String key(String requestId) {
        return "issue-request:" + requestId;
    }

    public void save(String requestId, Long couponId, Long userId, Instant requestedAt) {
        String key = key(requestId);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "couponId", String.valueOf(couponId),
                "userId", String.valueOf(userId),
                "requestedAt", requestedAt.toString()));
        redisTemplate.expire(key, TTL);
    }

    public Optional<PendingIssueRequest> find(String requestId) {
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key(requestId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PendingIssueRequest(requestId,
                Long.valueOf((String) hash.get("couponId")),
                Long.valueOf((String) hash.get("userId")),
                Instant.parse((String) hash.get("requestedAt"))));
    }

    public void delete(String requestId) {
        redisTemplate.delete(key(requestId));
    }

    public record PendingIssueRequest(String requestId, Long couponId, Long userId, Instant requestedAt) {
    }
}

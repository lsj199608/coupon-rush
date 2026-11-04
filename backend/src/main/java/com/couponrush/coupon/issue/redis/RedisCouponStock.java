package com.couponrush.coupon.issue.redis;

import com.couponrush.coupon.Coupon;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Redis 에 있는 쿠폰 재고. 모든 상태 변경은 Lua 스크립트로 원자적으로 한다.
 *
 * 키 (중괄호는 Redis Cluster hash tag — 세 키가 같은 슬롯에 들어가야 Lua 하나에서 함께 다룰 수 있다):
 *  - event:{couponId}:stock   남은 재고
 *  - event:{couponId}:issued  발급받은 userId Set
 *  - event:{couponId}:meta    발급 기간 (start, end epoch millis) — 발급 경로에서 DB 를 읽지 않기 위해
 * 만료: 발급 종료 + 7일
 */
@Component
public class RedisCouponStock {

    private static final Duration KEY_RETENTION_AFTER_END = Duration.ofDays(7);

    private static final DefaultRedisScript<String> ISSUE_SCRIPT = script("redis/coupon-issue.lua", String.class);
    private static final DefaultRedisScript<Long> LOAD_SCRIPT = script("redis/coupon-stock-load.lua", Long.class);
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT = script("redis/coupon-issue-restore.lua", Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCouponStock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String stockKey(Long couponId) {
        return "event:{" + couponId + "}:stock";
    }

    public static String issuedKey(Long couponId) {
        return "event:{" + couponId + "}:issued";
    }

    public static String metaKey(Long couponId) {
        return "event:{" + couponId + "}:meta";
    }

    /** 발급 판정 + (성공 시) 재고 감소와 Set 추가. 한 번의 왕복, 원자적 */
    public RedisIssueResult tryIssue(Long couponId, Long userId, Instant now) {
        String result = redisTemplate.execute(ISSUE_SCRIPT, keys(couponId),
                String.valueOf(userId), String.valueOf(now.toEpochMilli()));
        return RedisIssueResult.valueOf(result);
    }

    /**
     * 재고 적재. 기존 키를 지우고 다시 만든다.
     * ⚠ 발급이 진행 중일 때 호출하면, Redis 에서는 성공했지만 아직 DB 에 커밋되지 않은 발급이 사라져 초과 발급될 수 있다.
     *   이벤트 오픈 전, 또는 트래픽을 막은 상태(장애 복구)에서만 호출한다.
     *
     * @param issuedUserIds 이미 발급받은 사용자 (신규 쿠폰이면 빈 목록)
     */
    public void load(Coupon coupon, Collection<Long> issuedUserIds) {
        int remaining = coupon.getTotalQuantity() - issuedUserIds.size();
        if (remaining < 0) {
            throw new IllegalStateException("발급 이력이 총 수량보다 많습니다. couponId=" + coupon.getId());
        }
        long expireAt = coupon.getIssueEndAt().plus(KEY_RETENTION_AFTER_END).getEpochSecond();

        List<String> args = new ArrayList<>(4 + issuedUserIds.size());
        args.add(String.valueOf(remaining));
        args.add(String.valueOf(coupon.getIssueStartAt().toEpochMilli()));
        args.add(String.valueOf(coupon.getIssueEndAt().toEpochMilli()));
        args.add(String.valueOf(expireAt));
        issuedUserIds.forEach(id -> args.add(String.valueOf(id)));

        redisTemplate.execute(LOAD_SCRIPT, keys(coupon.getId()), args.toArray());
    }

    /**
     * 보상: Redis 에서 SUCCESS 였지만 DB 저장이 실패했을 때 재고를 되돌린다.
     * @param removeUser true 면 발급 Set 에서도 제거 (DB 에 발급 이력이 없는 경우)
     */
    public void restore(Long couponId, Long userId, boolean removeUser) {
        redisTemplate.execute(RESTORE_SCRIPT, keys(couponId).subList(0, 2),
                String.valueOf(userId), removeUser ? "1" : "0");
    }

    public Optional<Integer> getStock(Long couponId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(stockKey(couponId))).map(Integer::valueOf);
    }

    public long getIssuedCount(Long couponId) {
        Long size = redisTemplate.opsForSet().size(issuedKey(couponId));
        return size == null ? 0 : size;
    }

    private static List<String> keys(Long couponId) {
        return List.of(stockKey(couponId), issuedKey(couponId), metaKey(couponId));
    }

    /**
     * 스크립트 내용을 기동 시 한 번만 읽어 문자열로 고정한다.
     * ⚠ ResourceScriptSource 를 그대로 쓰면 DefaultRedisScript 가 호출마다 "파일이 바뀌었나"를
     *   동기화 블록 안에서 확인해(파일 시스템 조회) 모든 발급 요청이 한 줄로 직렬화된다.
     *   (실측: 32스레드 10,000회가 12.1초 → 수정 후 측정은 docs/concurrency-notes.md)
     * Spring 은 EVALSHA 로 호출하고, 서버에 스크립트가 없으면 EVAL 로 올린다.
     */
    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        try {
            String source = StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, resultType);
        } catch (IOException e) {
            throw new UncheckedIOException("Lua 스크립트를 읽을 수 없습니다: " + path, e);
        }
    }
}

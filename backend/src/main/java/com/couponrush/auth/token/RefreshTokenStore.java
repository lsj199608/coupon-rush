package com.couponrush.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * refresh:{userId} → SHA-256(refresh token)
 * - 원문 대신 해시를 저장: Redis 덤프/모니터링이 유출돼도 토큰을 바로 쓸 수 없다
 * - 사용자당 1개: 새로 로그인하면 이전 기기의 refresh token 은 무효가 된다 (다중 기기 지원 시 키에 기기 ID 추가)
 * - TTL = refresh token 유효기간 → 만료된 토큰은 Redis 가 알아서 지운다
 */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void save(Long userId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(userId), sha256(refreshToken), ttl);
    }

    /** 저장된 값과 같은지 확인. 해시 비교는 상수 시간(MessageDigest.isEqual)으로 한다 */
    public boolean matches(Long userId, String refreshToken) {
        String stored = redisTemplate.opsForValue().get(key(userId));
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                sha256(refreshToken).getBytes(StandardCharsets.UTF_8));
    }

    public void delete(Long userId) {
        redisTemplate.delete(key(userId));
    }

    static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);   // 모든 JVM 이 지원하므로 발생하지 않음
        }
    }
}

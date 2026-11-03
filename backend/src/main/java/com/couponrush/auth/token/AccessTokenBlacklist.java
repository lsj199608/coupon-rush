package com.couponrush.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로그아웃된 access token 의 jti 를 남은 유효시간만큼만 보관한다.
 * blacklist:{jti} → "logout", TTL = 토큰 만료까지 남은 시간
 * (만료된 토큰은 서명 검증 단계에서 이미 거부되므로 그 이후까지 보관할 필요가 없다)
 */
@Component
public class AccessTokenBlacklist {

    private static final String KEY_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    public AccessTokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void add(String tokenId, Duration remainingValidity) {
        if (remainingValidity.isNegative() || remainingValidity.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(key(tokenId), "logout", remainingValidity);
    }

    public boolean contains(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(tokenId)));
    }

    static String key(String tokenId) {
        return KEY_PREFIX + tokenId;
    }
}

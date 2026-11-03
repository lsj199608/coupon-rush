package com.couponrush.auth.token;

import com.couponrush.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class TokenStoreTest {

    @Autowired RefreshTokenStore refreshTokenStore;
    @Autowired AccessTokenBlacklist blacklist;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
        redisTemplate.delete(redisTemplate.keys("blacklist:*"));
    }

    @Test
    void refresh_token_은_refresh_userId_키에_해시로_TTL_과_함께_저장된다() {
        refreshTokenStore.save(42L, "raw.refresh.token", Duration.ofDays(14));

        String stored = redisTemplate.opsForValue().get("refresh:42");
        Long ttlSeconds = redisTemplate.getExpire("refresh:42", TimeUnit.SECONDS);

        assertThat(stored).isNotEqualTo("raw.refresh.token").hasSize(64);   // SHA-256 hex
        assertThat(ttlSeconds).isBetween(Duration.ofDays(14).toSeconds() - 60, Duration.ofDays(14).toSeconds());
    }

    @Test
    void 저장된_토큰과_같을_때만_일치한다() {
        refreshTokenStore.save(42L, "token-A", Duration.ofMinutes(5));

        assertThat(refreshTokenStore.matches(42L, "token-A")).isTrue();
        assertThat(refreshTokenStore.matches(42L, "token-B")).isFalse();
        assertThat(refreshTokenStore.matches(99L, "token-A")).isFalse();
    }

    @Test
    void 다시_저장하면_이전_토큰은_무효가_된다() {
        refreshTokenStore.save(42L, "old", Duration.ofMinutes(5));
        refreshTokenStore.save(42L, "new", Duration.ofMinutes(5));

        assertThat(refreshTokenStore.matches(42L, "old")).isFalse();
        assertThat(refreshTokenStore.matches(42L, "new")).isTrue();
    }

    @Test
    void 삭제하면_더_이상_일치하지_않는다() {
        refreshTokenStore.save(42L, "token", Duration.ofMinutes(5));

        refreshTokenStore.delete(42L);

        assertThat(redisTemplate.hasKey("refresh:42")).isFalse();
        assertThat(refreshTokenStore.matches(42L, "token")).isFalse();
    }

    @Test
    void 블랙리스트는_남은_유효시간만큼만_보관된다() {
        blacklist.add("jti-1", Duration.ofMinutes(10));

        Long ttlSeconds = redisTemplate.getExpire("blacklist:jti-1", TimeUnit.SECONDS);

        assertThat(blacklist.contains("jti-1")).isTrue();
        assertThat(blacklist.contains("jti-2")).isFalse();
        assertThat(ttlSeconds).isBetween(Duration.ofMinutes(10).toSeconds() - 60, Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void 이미_만료된_토큰은_블랙리스트에_넣지_않는다() {
        blacklist.add("expired-jti", Duration.ofSeconds(-5));

        assertThat(redisTemplate.hasKey("blacklist:expired-jti")).isFalse();
    }
}

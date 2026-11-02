package com.couponrush.auth.jwt;

import com.couponrush.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(30));

    @Test
    void 발급한_토큰을_검증하면_userId_와_role_을_얻는다() {
        JwtTokenProvider provider = new JwtTokenProvider(properties);

        String token = provider.createAccessToken(7L, Role.USER);
        AccessTokenClaims claims = provider.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo(Role.USER);
    }

    @Test
    void 유효기간이_지난_토큰은_거부된다() {
        String token = new JwtTokenProvider(properties, fixedClock(NOW)).createAccessToken(1L, Role.USER);
        JwtTokenProvider after31Minutes = new JwtTokenProvider(properties, fixedClock(NOW.plus(Duration.ofMinutes(31))));

        assertThatThrownBy(() -> after31Minutes.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_거부된다() {
        JwtTokenProvider other = new JwtTokenProvider(
                new JwtProperties("another-secret-key-that-is-also-32-bytes-long", Duration.ofMinutes(30)));
        String forged = other.createAccessToken(1L, Role.ADMIN);

        assertThatThrownBy(() -> new JwtTokenProvider(properties).parseAccessToken(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void 시크릿이_32바이트보다_짧으면_생성_단계에서_실패한다() {
        assertThatThrownBy(() -> new JwtTokenProvider(new JwtProperties("too-short", Duration.ofMinutes(30))))
                .isInstanceOf(WeakKeyException.class);
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}

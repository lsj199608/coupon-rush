package com.couponrush.auth.jwt;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.Role;
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
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private final JwtTokenProvider provider = providerAt(NOW);

    @Test
    void access_token_을_검증하면_userId_role_jti_만료시각을_얻는다() {
        String token = provider.createAccessToken(7L, Role.USER);

        AccessTokenClaims claims = provider.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo(Role.USER);
        assertThat(claims.tokenId()).isNotBlank();
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void 토큰마다_jti_가_다르다() {
        String first = provider.createAccessToken(1L, Role.USER);
        String second = provider.createAccessToken(1L, Role.USER);

        assertThat(provider.parseAccessToken(first).tokenId())
                .isNotEqualTo(provider.parseAccessToken(second).tokenId());
    }

    @Test
    void refresh_token_은_14일간_유효하다() {
        String token = provider.createRefreshToken(7L);

        RefreshTokenClaims claims = providerAt(NOW.plus(Duration.ofDays(13))).parseRefreshToken(token);

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void refresh_token_을_access_token_으로_쓸_수_없다() {
        String refresh = provider.createRefreshToken(1L);

        assertErrorCode(() -> provider.parseAccessToken(refresh), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void access_token_을_refresh_token_으로_쓸_수_없다() {
        String access = provider.createAccessToken(1L, Role.USER);

        assertErrorCode(() -> provider.parseRefreshToken(access), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 만료된_access_token_은_TOKEN_EXPIRED() {
        String token = provider.createAccessToken(1L, Role.USER);

        assertErrorCode(() -> providerAt(NOW.plus(Duration.ofMinutes(31))).parseAccessToken(token),
                ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 만료된_refresh_token_은_TOKEN_EXPIRED() {
        String token = provider.createRefreshToken(1L);

        assertErrorCode(() -> providerAt(NOW.plus(Duration.ofDays(15))).parseRefreshToken(token),
                ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 다른_키로_서명된_토큰은_TOKEN_INVALID() {
        JwtTokenProvider other = new JwtTokenProvider(new JwtProperties(
                "another-secret-key-that-is-also-32-bytes-long", Duration.ofMinutes(30), Duration.ofDays(14)),
                fixedClock(NOW));
        String forged = other.createAccessToken(1L, Role.ADMIN);

        assertErrorCode(() -> provider.parseAccessToken(forged), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 형식이_잘못된_토큰은_TOKEN_INVALID() {
        assertErrorCode(() -> provider.parseAccessToken("not-a-jwt"), ErrorCode.TOKEN_INVALID);
        assertErrorCode(() -> provider.parseAccessToken(""), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 시크릿이_32바이트보다_짧으면_생성_단계에서_실패한다() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                new JwtProperties("too-short", Duration.ofMinutes(30), Duration.ofDays(14)), fixedClock(NOW)))
                .isInstanceOf(WeakKeyException.class);
    }

    private JwtTokenProvider providerAt(Instant instant) {
        return new JwtTokenProvider(properties, fixedClock(instant));
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}

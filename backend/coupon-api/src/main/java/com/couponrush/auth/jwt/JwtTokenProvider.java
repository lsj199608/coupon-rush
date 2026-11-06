package com.couponrush.auth.jwt;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Access / Refresh token 발급과 검증.
 * - 토큰에는 userId(sub), 용도(token_type), 고유 ID(jti)만 담고 access 에만 role 을 추가한다.
 *   (JWT payload 는 Base64 인코딩일 뿐이라 누구나 읽을 수 있어 개인정보는 넣지 않는다)
 * - jjwt 예외는 밖으로 내보내지 않고 ErrorCode(TOKEN_EXPIRED / TOKEN_INVALID)로 바꿔 던진다.
 */
@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration accessTokenValidity;
    private final Duration refreshTokenValidity;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        // 32바이트 미만이면 WeakKeyException → 잘못된 설정으로 서버가 뜨는 것을 막는다
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidity = properties.accessTokenValidity();
        this.refreshTokenValidity = properties.refreshTokenValidity();
        this.clock = clock;
    }

    public String createAccessToken(Long userId, Role role) {
        return baseBuilder(userId, TokenType.ACCESS, accessTokenValidity)
                .claim(ROLE_CLAIM, role.name())
                .compact();
    }

    public String createRefreshToken(Long userId) {
        return baseBuilder(userId, TokenType.REFRESH, refreshTokenValidity).compact();
    }

    /** @throws BusinessException TOKEN_EXPIRED / TOKEN_INVALID (위조, 형식 오류, refresh token 을 넣은 경우 포함) */
    public AccessTokenClaims parseAccessToken(String token) {
        Claims claims = parse(token, TokenType.ACCESS);
        return new AccessTokenClaims(userId(claims), role(claims), claims.getId(), claims.getExpiration().toInstant());
    }

    /** @throws BusinessException TOKEN_EXPIRED / TOKEN_INVALID (access token 을 넣은 경우 포함) */
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = parse(token, TokenType.REFRESH);
        return new RefreshTokenClaims(userId(claims), claims.getExpiration().toInstant());
    }

    public Duration getAccessTokenValidity() {
        return accessTokenValidity;
    }

    public Duration getRefreshTokenValidity() {
        return refreshTokenValidity;
    }

    private JwtBuilder baseBuilder(Long userId, TokenType type, Duration validity) {
        Instant now = clock.instant();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(TOKEN_TYPE_CLAIM, type.claimValue())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(key);
    }

    private Claims parse(String token, TokenType expectedType) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        if (!expectedType.claimValue().equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return claims;
    }

    private static Long userId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    private static Role role(Claims claims) {
        try {
            return Role.valueOf(claims.get(ROLE_CLAIM, String.class));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }
}

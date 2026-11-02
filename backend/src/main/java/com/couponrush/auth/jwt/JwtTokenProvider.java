package com.couponrush.auth.jwt;

import com.couponrush.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Access token 발급/검증.
 * 토큰에는 userId(sub) 와 role 만 담는다 — 닉네임·이메일 같은 개인정보는 넣지 않는다
 * (JWT payload 는 암호화가 아니라 Base64 인코딩이라 누구나 읽을 수 있다).
 */
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration accessTokenValidity;
    private final Clock clock;

    @Autowired
    public JwtTokenProvider(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트에서 시간을 고정하기 위한 생성자 */
    JwtTokenProvider(JwtProperties properties, Clock clock) {
        // 32바이트 미만이면 WeakKeyException → 잘못된 설정으로 서버가 뜨는 것을 막는다
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidity = properties.accessTokenValidity();
        this.clock = clock;
    }

    public String createAccessToken(Long userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenValidity)))
                .signWith(key)
                .compact();
    }

    /**
     * 서명·만료를 검증하고 claims 를 꺼낸다. (2단계 후반 JWT 인증 필터에서 사용)
     *
     * @throws JwtException 위조/만료/형식 오류
     */
    public AccessTokenClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AccessTokenClaims(
                Long.valueOf(claims.getSubject()),
                Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
    }
}

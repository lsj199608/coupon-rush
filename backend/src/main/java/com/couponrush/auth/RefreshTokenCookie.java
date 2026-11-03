package com.couponrush.auth;

import com.couponrush.auth.jwt.JwtTokenProvider;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * refresh token 쿠키.
 * - HttpOnly: JavaScript 에서 읽을 수 없어 XSS 로 탈취되지 않는다
 * - Path=/api/auth: refresh/logout 요청에만 실려 가고 일반 API 요청에는 붙지 않는다
 * - SameSite=Strict: 다른 사이트에서 시작된 요청에는 실리지 않아 CSRF 를 막는다
 *   (localhost:5173 → localhost:8080 은 포트만 다른 same-site 라 정상 전송된다.
 *    배포 시 프론트/백엔드 도메인이 다른 사이트가 되면 SameSite=None + Secure 로 바꿔야 한다)
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/auth";

    private final Duration maxAge;
    private final boolean secure;

    public RefreshTokenCookie(JwtTokenProvider jwtTokenProvider, AuthCookieProperties properties) {
        this.maxAge = jwtTokenProvider.getRefreshTokenValidity();
        this.secure = properties.secure();
    }

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    /** 로그아웃 시 브라우저에서 쿠키를 지운다 (같은 이름/경로 + Max-Age=0) */
    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}

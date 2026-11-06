package com.couponrush.auth;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie) {
        this.authService = authService;
        this.refreshTokenCookie = refreshTokenCookie;
    }

    /** refresh token(쿠키)으로 새 access token 발급. access token 이 만료된 상태에서 호출하므로 인증 불필요 */
    @PostMapping("/refresh")
    public AccessTokenResponse refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISSING);
        }
        return authService.refresh(refreshToken);
    }

    /** 로그아웃: 유효한 access token 필요 (어떤 토큰을 블랙리스트에 넣을지 알아야 하므로) */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthUser authUser) {
        authService.logout(authUser);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.expire().toString())
                .build();
    }
}

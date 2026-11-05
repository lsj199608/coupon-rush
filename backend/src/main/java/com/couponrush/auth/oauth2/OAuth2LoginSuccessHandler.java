package com.couponrush.auth.oauth2;

import com.couponrush.auth.AuthService;
import com.couponrush.auth.LoginTokens;
import com.couponrush.auth.RefreshTokenCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 성공 → refresh token 을 HttpOnly 쿠키로 심고 → 프론트엔드 콜백 주소로 리다이렉트 (URL 에 토큰을 싣지 않는다).
 * - refresh token: HttpOnly 쿠키. JavaScript 가 읽을 수 없어 XSS 로 탈취되지 않는다.
 * - access token: 프론트가 콜백 페이지에서 POST /api/auth/refresh 로 받아 메모리에만 둔다.
 *   (예전에는 URL fragment 로 넘겼다. fragment 는 서버로 가지 않지만 브라우저 방문 기록·주소창·페이지의 모든 스크립트에 노출된다.
 *    새로고침 후 복원에도 어차피 refresh 호출이 필요하므로, 로그인 직후도 같은 경로를 쓰게 해서 토큰이 URL 에 나타나지 않게 했다)
 * 로그인 직후 발급한 access token 은 쓰이지 않고 버려진다 — refresh 시 새로 발급된다 (jti 가 달라 블랙리스트와도 무관).
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final OAuth2RedirectProperties redirectProperties;

    public OAuth2LoginSuccessHandler(AuthService authService, RefreshTokenCookie refreshTokenCookie,
                                     OAuth2RedirectProperties redirectProperties) {
        this.authService = authService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.redirectProperties = redirectProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        LoginTokens tokens = authService.login(loginUser.getUserId(), loginUser.getRole());

        // OAuth2 인가 요청(state) 보관용으로만 쓰인 세션 정리 — 이후 인증은 JWT 로만 한다
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.create(tokens.refreshToken()).toString());

        response.sendRedirect(redirectProperties.redirectUri());   // 토큰 없이. 프론트가 쿠키로 /api/auth/refresh 호출
    }
}

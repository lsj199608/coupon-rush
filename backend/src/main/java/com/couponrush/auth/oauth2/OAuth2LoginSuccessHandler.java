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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 로그인 성공 → access + refresh 발급 → 프론트엔드로 리다이렉트.
 * - access token: URL fragment(#) 로 전달. fragment 는 서버로 전송되지 않아 서버 로그·Referer 에 남지 않는다.
 *   프론트엔드는 이를 메모리에 보관한다.
 * - refresh token: HttpOnly 쿠키로 전달. JavaScript 가 읽을 수 없어 XSS 로 탈취되지 않는다.
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

        String targetUrl = UriComponentsBuilder.fromUriString(redirectProperties.redirectUri())
                .fragment("accessToken=" + tokens.accessToken())
                .build()
                .toUriString();
        response.sendRedirect(targetUrl);
    }
}

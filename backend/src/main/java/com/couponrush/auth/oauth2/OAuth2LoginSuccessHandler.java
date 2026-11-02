package com.couponrush.auth.oauth2;

import com.couponrush.auth.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 로그인 성공 → access token 발급 → 프론트엔드로 리다이렉트.
 * 토큰은 query(?) 가 아니라 fragment(#) 로 전달한다.
 * fragment 는 브라우저가 서버로 보내지 않아 서버 로그·프록시·Referer 헤더에 토큰이 남지 않는다.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final OAuth2RedirectProperties redirectProperties;

    public OAuth2LoginSuccessHandler(JwtTokenProvider jwtTokenProvider, OAuth2RedirectProperties redirectProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redirectProperties = redirectProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        String accessToken = jwtTokenProvider.createAccessToken(loginUser.getUserId(), loginUser.getRole());

        // OAuth2 인가 요청(state) 보관용으로만 쓰인 세션 정리 — 이후 인증은 JWT 로만 한다
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String targetUrl = UriComponentsBuilder.fromUriString(redirectProperties.redirectUri())
                .fragment("accessToken=" + accessToken)
                .build()
                .toUriString();
        response.sendRedirect(targetUrl);
    }
}

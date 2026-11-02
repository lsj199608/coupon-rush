package com.couponrush.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 로그인 실패(사용자가 동의 취소, 카카오 오류 등) 시 프론트엔드로 에러 코드만 전달한다.
 * 상세 사유는 서버 로그에만 남겨 내부 정보가 노출되지 않게 한다.
 */
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final OAuth2RedirectProperties redirectProperties;

    public OAuth2LoginFailureHandler(OAuth2RedirectProperties redirectProperties) {
        this.redirectProperties = redirectProperties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 로그인 실패: {}", exception.getMessage());

        String targetUrl = UriComponentsBuilder.fromUriString(redirectProperties.redirectUri())
                .fragment("error=login_failed")
                .build()
                .toUriString();
        response.sendRedirect(targetUrl);
    }
}

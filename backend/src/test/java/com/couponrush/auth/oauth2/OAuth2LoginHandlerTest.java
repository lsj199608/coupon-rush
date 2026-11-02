package com.couponrush.auth.oauth2;

import com.couponrush.auth.jwt.AccessTokenClaims;
import com.couponrush.auth.jwt.JwtProperties;
import com.couponrush.auth.jwt.JwtTokenProvider;
import com.couponrush.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginHandlerTest {

    private static final String FRONT_URL = "http://localhost:5173/oauth/callback";

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("test-jwt-secret-key-must-be-at-least-32-bytes", Duration.ofMinutes(30)));
    private final OAuth2RedirectProperties redirectProperties = new OAuth2RedirectProperties(FRONT_URL);

    @Test
    void 로그인_성공시_토큰을_fragment_에_담아_프론트로_리다이렉트하고_세션을_정리한다() throws Exception {
        OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(jwtTokenProvider, redirectProperties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoginUser principal = new LoginUser(10L, Role.USER, Map.of("id", 999L));

        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(principal, null));

        String redirectUrl = response.getRedirectedUrl();
        assertThat(redirectUrl).startsWith(FRONT_URL + "#accessToken=");
        assertThat(redirectUrl).doesNotContain("?");   // query 로 새지 않는다

        String token = redirectUrl.substring(redirectUrl.indexOf("accessToken=") + "accessToken=".length());
        AccessTokenClaims claims = jwtTokenProvider.parseAccessToken(token);
        assertThat(claims.userId()).isEqualTo(10L);
        assertThat(claims.role()).isEqualTo(Role.USER);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 로그인_실패시_에러코드만_담아_프론트로_리다이렉트한다() throws Exception {
        OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler(redirectProperties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "내부 상세 사유"));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT_URL + "#error=login_failed");
    }
}

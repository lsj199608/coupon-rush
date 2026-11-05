package com.couponrush.auth.oauth2;

import com.couponrush.auth.AuthCookieProperties;
import com.couponrush.auth.AuthService;
import com.couponrush.auth.LoginTokens;
import com.couponrush.auth.RefreshTokenCookie;
import com.couponrush.auth.jwt.JwtProperties;
import com.couponrush.auth.jwt.JwtTokenProvider;
import com.couponrush.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OAuth2LoginHandlerTest {

    private static final String FRONT_URL = "http://localhost:5173/oauth/callback";

    private final OAuth2RedirectProperties redirectProperties = new OAuth2RedirectProperties(FRONT_URL);
    private final RefreshTokenCookie refreshTokenCookie = new RefreshTokenCookie(
            new JwtTokenProvider(new JwtProperties("test-jwt-secret-key-must-be-at-least-32-bytes",
                    Duration.ofMinutes(30), Duration.ofDays(14)), Clock.systemUTC()),
            new AuthCookieProperties(false));

    @Test
    void 로그인_성공시_URL_에는_토큰_없이_리다이렉트하고_refresh_는_HttpOnly_쿠키로_전달하고_세션을_정리한다() throws Exception {
        AuthService authService = mock(AuthService.class);
        given(authService.login(10L, Role.USER)).willReturn(new LoginTokens("ACCESS.TOKEN", "REFRESH.TOKEN"));
        OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(authService, refreshTokenCookie, redirectProperties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoginUser principal = new LoginUser(10L, Role.USER, Map.of("id", 999L));

        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(principal, null));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT_URL);
        assertThat(response.getRedirectedUrl()).as("access token 은 URL 에 싣지 않는다 (방문 기록·주소창 노출 방지)").doesNotContain("ACCESS.TOKEN");

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("refresh_token=REFRESH.TOKEN")
                .contains("Path=/api/auth", "HttpOnly", "SameSite=Strict", "Max-Age=" + Duration.ofDays(14).toSeconds())
                .doesNotContain("Secure");   // 로컬 http 설정
        assertThat(response.getRedirectedUrl()).doesNotContain("REFRESH.TOKEN");   // refresh 는 URL 에 절대 싣지 않는다

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

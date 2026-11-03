package com.couponrush.auth;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.jwt.JwtProperties;
import com.couponrush.auth.jwt.JwtTokenProvider;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 HTTP 흐름 검증: Bearer 인증 필터, /api/users/me, refresh, logout(+블랙리스트).
 * 소셜 로그인 단계는 건너뛰고 AuthService.login 으로 토큰을 직접 발급받아 시작한다.
 */
@IntegrationTest
class AuthApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired JwtProperties jwtProperties;
    @Autowired StringRedisTemplate redisTemplate;

    User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "auth-" + UUID.randomUUID(), "테스터", null));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllInBatch();
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
        redisTemplate.delete(redisTemplate.keys("blacklist:*"));
    }

    @Nested
    class 보호된_API_접근 {

        @Test
        void 토큰이_없으면_401_UNAUTHORIZED() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        @Test
        void 유효한_access_token_이면_내_정보를_돌려준다() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            me(tokens.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(user.getId()))
                    .andExpect(jsonPath("$.provider").value("KAKAO"))
                    .andExpect(jsonPath("$.nickname").value("테스터"))
                    .andExpect(jsonPath("$.role").value("USER"));
        }

        @Test
        void 만료된_access_token_이면_401_TOKEN_EXPIRED() throws Exception {
            JwtTokenProvider oneHourAgo = new JwtTokenProvider(jwtProperties,
                    Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
            String expired = oneHourAgo.createAccessToken(user.getId(), user.getRole());

            me(expired)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        }

        @Test
        void 위조되거나_형식이_틀린_토큰이면_401_TOKEN_INVALID() throws Exception {
            me("not.a.jwt")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        @Test
        void refresh_token_을_Bearer_로_보내면_401_TOKEN_INVALID() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            me(tokens.refreshToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }
    }

    @Nested
    class 토큰_재발급 {

        @Test
        void refresh_token_쿠키로_새_access_token_을_받고_그_토큰으로_API_를_호출할_수_있다() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            String body = refresh(tokens.refreshToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresIn").value(Duration.ofMinutes(30).toSeconds()))
                    .andReturn().getResponse().getContentAsString();
            String newAccessToken = objectMapper.readTree(body).get("accessToken").asText();

            assertThat(newAccessToken).isNotEqualTo(tokens.accessToken());
            me(newAccessToken).andExpect(status().isOk());
        }

        @Test
        void access_token_이_만료돼_Bearer_로_붙어_와도_재발급은_된다() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            mockMvc.perform(post("/api/auth/refresh")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.broken")
                            .cookie(new Cookie(RefreshTokenCookie.NAME, tokens.refreshToken())))
                    .andExpect(status().isOk());
        }

        @Test
        void 쿠키가_없으면_401_REFRESH_TOKEN_MISSING() throws Exception {
            mockMvc.perform(post("/api/auth/refresh"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_MISSING"));
        }

        @Test
        void Redis_에_저장된_값과_다르면_401_REFRESH_TOKEN_MISMATCH_재로그인으로_덮어쓴_경우() throws Exception {
            LoginTokens firstLogin = authService.login(user.getId(), user.getRole());
            authService.login(user.getId(), user.getRole());   // 다른 기기에서 다시 로그인

            refresh(firstLogin.refreshToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_MISMATCH"));
        }

        @Test
        void access_token_을_refresh_쿠키에_넣으면_401_TOKEN_INVALID() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            refresh(tokens.accessToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }
    }

    @Nested
    class 로그아웃 {

        @Test
        void 로그아웃하면_refresh_가_삭제되고_쿠키가_지워지며_쓰던_access_token_도_거부된다() throws Exception {
            LoginTokens tokens = authService.login(user.getId(), user.getRole());

            mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(tokens.accessToken())))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

            assertThat(redisTemplate.hasKey("refresh:" + user.getId())).isFalse();

            // 아직 만료 전인 access token → 블랙리스트로 거부
            me(tokens.accessToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));

            // refresh token → Redis 에서 지워졌으므로 재발급 불가
            refresh(tokens.refreshToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_MISMATCH"));
        }

        @Test
        void 로그아웃해도_다시_로그인하면_새_토큰은_정상_동작한다() throws Exception {
            LoginTokens before = authService.login(user.getId(), user.getRole());
            mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(before.accessToken())))
                    .andExpect(status().isNoContent());

            LoginTokens after = authService.login(user.getId(), user.getRole());

            me(after.accessToken()).andExpect(status().isOk());
            refresh(after.refreshToken()).andExpect(status().isOk());
        }

        @Test
        void 토큰_없이_로그아웃하면_401() throws Exception {
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void 토큰은_유효하지만_회원이_삭제됐으면_404_USER_NOT_FOUND() throws Exception {
        LoginTokens tokens = authService.login(user.getId(), user.getRole());
        userRepository.deleteById(user.getId());

        JsonNode body = objectMapper.readTree(me(tokens.accessToken())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("code").asText()).isEqualTo("USER_NOT_FOUND");
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(RefreshTokenCookie.NAME, refreshToken)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

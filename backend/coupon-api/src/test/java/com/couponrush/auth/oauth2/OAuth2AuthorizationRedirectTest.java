package com.couponrush.auth.oauth2;

import com.couponrush.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 제공자 서버 없이 검증할 수 있는 범위: 로그인 진입 시 각 제공자의 인가 페이지로 올바르게 보내는지.
 * (제공자 로그인 화면 이후 과정은 수동 테스트로 확인)
 */
@IntegrationTest
class OAuth2AuthorizationRedirectTest {

    @Autowired MockMvc mockMvc;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            kakao  | kauth.kakao.com     | /oauth/authorize      | test-kakao-client-id  | profile_nickname
            naver  | nid.naver.com       | /oauth2.0/authorize   | test-naver-client-id  | nickname email
            google | accounts.google.com | /o/oauth2/v2/auth     | test-google-client-id | profile email
            """)
    void 로그인_진입시_제공자_인가_페이지로_리다이렉트한다(String registrationId, String host, String path,
                                         String clientId, String scope) throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/" + registrationId))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        UriComponents uri = UriComponentsBuilder.fromUriString(location).build();
        assertThat(uri.getHost()).isEqualTo(host);
        assertThat(uri.getPath()).isEqualTo(path);
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo(clientId);
        assertThat(uri.getQueryParams().getFirst("response_type")).isEqualTo("code");
        assertThat(decode(uri.getQueryParams().getFirst("scope"))).isEqualTo(scope);
        assertThat(uri.getQueryParams().getFirst("state")).isNotBlank();   // CSRF 방지용 state
        assertThat(decode(uri.getQueryParams().getFirst("redirect_uri")))
                .endsWith("/login/oauth2/code/" + registrationId);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}

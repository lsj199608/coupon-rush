package com.couponrush.auth.oauth2;

import com.couponrush.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 카카오 서버 없이 검증할 수 있는 범위: 로그인 진입 시 카카오 인가 페이지로 올바르게 보내는지.
 * (카카오 로그인 화면 이후 과정은 수동 테스트로 확인)
 */
@IntegrationTest
class KakaoLoginFlowTest {

    @Autowired MockMvc mockMvc;

    @Test
    void 카카오_로그인_진입시_카카오_인가_페이지로_리다이렉트한다() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        UriComponents uri = UriComponentsBuilder.fromUriString(location).build();
        assertThat(uri.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(uri.getPath()).isEqualTo("/oauth/authorize");
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo("test-kakao-client-id");
        assertThat(uri.getQueryParams().getFirst("response_type")).isEqualTo("code");
        assertThat(uri.getQueryParams().getFirst("scope")).isEqualTo("profile_nickname");
        assertThat(uri.getQueryParams().getFirst("state")).isNotBlank();   // CSRF 방지용 state
        assertThat(uri.getQueryParams().getFirst("redirect_uri"))
                .contains("/login/oauth2/code/kakao");
    }
}

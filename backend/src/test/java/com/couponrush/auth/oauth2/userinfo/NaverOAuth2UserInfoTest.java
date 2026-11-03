package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverOAuth2UserInfoTest {

    @Test
    void 중첩된_response_에서_ID_닉네임_이메일을_꺼낸다() {
        Map<String, Object> attributes = naver(Map.of(
                "id", "abcdEFGH1234",
                "nickname", "네이버닉",
                "name", "홍길동",
                "email", "hong@naver.com"));

        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(attributes);

        assertThat(userInfo.getProvider()).isEqualTo(OAuth2Provider.NAVER);
        assertThat(userInfo.getProviderId()).isEqualTo("abcdEFGH1234");
        assertThat(userInfo.getNickname()).isEqualTo("네이버닉");
        assertThat(userInfo.getEmail()).contains("hong@naver.com");
    }

    @Test
    void 이메일이_없어도_정상_처리된다() {
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(naver(Map.of("id", "n1", "nickname", "닉")));

        assertThat(userInfo.getEmail()).isEmpty();
    }

    @Test
    void 이메일이_null_로_와도_없는_것으로_처리한다() {
        Map<String, Object> response = new HashMap<>();
        response.put("id", "n1");
        response.put("email", null);

        assertThat(new NaverOAuth2UserInfo(naver(response)).getEmail()).isEmpty();
    }

    @Test
    void 별명이_없으면_이름을_쓴다() {
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(naver(Map.of("id", "n1", "name", "홍길동")));

        assertThat(userInfo.getNickname()).isEqualTo("홍길동");
    }

    @Test
    void 별명도_이름도_없으면_기본_닉네임을_쓴다() {
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(naver(Map.of("id", "n1")));

        assertThat(userInfo.getNickname()).isEqualTo("naver_n1");
    }

    @Test
    void response_가_없거나_id_가_없으면_예외() {
        assertThatThrownBy(() -> new NaverOAuth2UserInfo(Map.of("resultcode", "00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NaverOAuth2UserInfo(naver(Map.of("nickname", "닉"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> naver(Map<String, Object> response) {
        return Map.of("resultcode", "00", "message", "success", "response", response);
    }
}

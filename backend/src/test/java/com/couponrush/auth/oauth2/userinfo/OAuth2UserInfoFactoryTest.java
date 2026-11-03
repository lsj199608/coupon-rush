package com.couponrush.auth.oauth2.userinfo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2UserInfoFactoryTest {

    @Test
    void registrationId_로_제공자별_구현체를_고른다() {
        assertThat(OAuth2UserInfoFactory.of("kakao", Map.of("id", 1L)))
                .isInstanceOf(KakaoOAuth2UserInfo.class);
        assertThat(OAuth2UserInfoFactory.of("naver", Map.of("response", Map.of("id", "n1"))))
                .isInstanceOf(NaverOAuth2UserInfo.class);
        assertThat(OAuth2UserInfoFactory.of("google", Map.of("sub", "g1")))
                .isInstanceOf(GoogleOAuth2UserInfo.class);
    }

    @Test
    void 지원하지_않는_제공자면_예외() {
        assertThatThrownBy(() -> OAuth2UserInfoFactory.of("facebook", Map.of("id", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoOAuth2UserInfoTest {

    @Test
    void 카카오_응답에서_ID_닉네임_이메일을_꺼낸다() {
        Map<String, Object> attributes = Map.of(
                "id", 123456789L,
                "kakao_account", Map.of(
                        "profile", Map.of("nickname", "홍길동"),
                        "email", "hong@example.com"));

        KakaoOAuth2UserInfo userInfo = new KakaoOAuth2UserInfo(attributes);

        assertThat(userInfo.getProvider()).isEqualTo(OAuth2Provider.KAKAO);
        assertThat(userInfo.getProviderId()).isEqualTo("123456789");
        assertThat(userInfo.getNickname()).isEqualTo("홍길동");
        assertThat(userInfo.getEmail()).contains("hong@example.com");
    }

    @Test
    void 이메일이_없어도_정상_처리된다() {
        Map<String, Object> attributes = Map.of(
                "id", 1L,
                "kakao_account", Map.of("profile", Map.of("nickname", "닉네임")));

        assertThat(new KakaoOAuth2UserInfo(attributes).getEmail()).isEmpty();
    }

    @Test
    void 닉네임_동의를_안하면_기본_닉네임을_쓴다() {
        Map<String, Object> attributes = Map.of("id", 42L);

        KakaoOAuth2UserInfo userInfo = new KakaoOAuth2UserInfo(attributes);

        assertThat(userInfo.getNickname()).isEqualTo("kakao_42");
        assertThat(userInfo.getEmail()).isEmpty();
    }

    @Test
    void 닉네임이_컬럼_길이보다_길면_잘라낸다() {
        Map<String, Object> attributes = Map.of(
                "id", 1L,
                "kakao_account", Map.of("profile", Map.of("nickname", "가".repeat(60))));

        assertThat(new KakaoOAuth2UserInfo(attributes).getNickname()).hasSize(50);
    }

    @Test
    void id_가_없으면_예외() {
        assertThatThrownBy(() -> new KakaoOAuth2UserInfo(Map.of("kakao_account", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 팩토리는_registrationId_로_구현체를_고른다() {
        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.of("kakao", Map.of("id", 1L));

        assertThat(userInfo).isInstanceOf(KakaoOAuth2UserInfo.class);
    }

    @Test
    void 팩토리는_지원하지_않는_제공자면_예외() {
        assertThatThrownBy(() -> OAuth2UserInfoFactory.of("facebook", Map.of("id", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

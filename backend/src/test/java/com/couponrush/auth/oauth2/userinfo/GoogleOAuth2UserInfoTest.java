package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleOAuth2UserInfoTest {

    @Test
    void sub_이름_인증된_이메일을_꺼낸다() {
        GoogleOAuth2UserInfo userInfo = new GoogleOAuth2UserInfo(Map.of(
                "sub", "1098765432101234567",
                "name", "홍길동",
                "email", "hong@gmail.com",
                "email_verified", true));

        assertThat(userInfo.getProvider()).isEqualTo(OAuth2Provider.GOOGLE);
        assertThat(userInfo.getProviderId()).isEqualTo("1098765432101234567");
        assertThat(userInfo.getNickname()).isEqualTo("홍길동");
        assertThat(userInfo.getEmail()).contains("hong@gmail.com");
    }

    @Test
    void 인증되지_않은_이메일은_저장하지_않는다() {
        GoogleOAuth2UserInfo userInfo = new GoogleOAuth2UserInfo(Map.of(
                "sub", "g1", "email", "unverified@example.com", "email_verified", false));

        assertThat(userInfo.getEmail()).isEmpty();
    }

    @Test
    void 이메일_정보가_없어도_정상_처리된다() {
        GoogleOAuth2UserInfo userInfo = new GoogleOAuth2UserInfo(Map.of("sub", "g1", "name", "이름"));

        assertThat(userInfo.getEmail()).isEmpty();
    }

    @Test
    void 이름이_없으면_기본_닉네임을_쓴다() {
        assertThat(new GoogleOAuth2UserInfo(Map.of("sub", "g1")).getNickname()).isEqualTo("google_g1");
    }

    @Test
    void sub_가_없으면_예외() {
        assertThatThrownBy(() -> new GoogleOAuth2UserInfo(Map.of("name", "이름")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

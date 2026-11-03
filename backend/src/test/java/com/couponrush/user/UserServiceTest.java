package com.couponrush.user;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.oauth2.userinfo.GoogleOAuth2UserInfo;
import com.couponrush.auth.oauth2.userinfo.KakaoOAuth2UserInfo;
import com.couponrush.auth.oauth2.userinfo.NaverOAuth2UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class UserServiceTest {

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.deleteAllInBatch();
    }

    @Test
    void 처음_로그인하면_회원이_저장된다_이메일_없어도_됨() {
        User user = userService.loginOrRegister(kakao(1001L, "첫로그인", null));

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getProvider()).isEqualTo(OAuth2Provider.KAKAO);
        assertThat(saved.getProviderId()).isEqualTo("1001");
        assertThat(saved.getNickname()).isEqualTo("첫로그인");
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void 다시_로그인하면_새로_만들지_않고_닉네임을_갱신한다() {
        User first = userService.loginOrRegister(kakao(2002L, "옛닉네임", "a@example.com"));

        User second = userService.loginOrRegister(kakao(2002L, "새닉네임", null));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
        User reloaded = userRepository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("새닉네임");
        assertThat(reloaded.getEmail()).isEqualTo("a@example.com");   // 이번에 못 받았다고 지우지 않는다
    }

    @Test
    void 카카오_ID_가_다르면_다른_회원이다_닉네임이_같아도() {
        userService.loginOrRegister(kakao(3001L, "같은닉네임", null));
        userService.loginOrRegister(kakao(3002L, "같은닉네임", null));

        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_사람이_여러_제공자로_로그인하면_제공자별로_각각_회원이_된다() {
        // 같은 이메일/닉네임이어도 이메일로 계정을 합치지 않는다 (provider, providerId 가 식별 키)
        User byKakao = userService.loginOrRegister(kakao(5001L, "홍길동", "same@example.com"));
        User byNaver = userService.loginOrRegister(naver("n-5001", "홍길동", "same@example.com"));
        User byGoogle = userService.loginOrRegister(google("g-5001", "홍길동", "same@example.com"));

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(java.util.List.of(byKakao.getProvider(), byNaver.getProvider(), byGoogle.getProvider()))
                .containsExactly(OAuth2Provider.KAKAO, OAuth2Provider.NAVER, OAuth2Provider.GOOGLE);
    }

    @Test
    void 각_제공자로_다시_로그인해도_중복_회원이_생기지_않는다() {
        User naverFirst = userService.loginOrRegister(naver("n-6001", "네이버", null));
        User googleFirst = userService.loginOrRegister(google("g-6001", "구글", null));

        User naverAgain = userService.loginOrRegister(naver("n-6001", "네이버변경", null));
        User googleAgain = userService.loginOrRegister(google("g-6001", "구글변경", null));

        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(naverAgain.getId()).isEqualTo(naverFirst.getId());
        assertThat(googleAgain.getId()).isEqualTo(googleFirst.getId());
    }

    @Test
    void 제공자가_달라도_providerId_값이_우연히_같으면_다른_회원이다() {
        userService.loginOrRegister(naver("12345", "네이버", null));
        userService.loginOrRegister(google("12345", "구글", null));

        assertThat(userRepository.count()).isEqualTo(2);
    }

    private static NaverOAuth2UserInfo naver(String id, String nickname, String email) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("nickname", nickname);
        if (email != null) {
            response.put("email", email);
        }
        return new NaverOAuth2UserInfo(Map.of("resultcode", "00", "response", response));
    }

    private static GoogleOAuth2UserInfo google(String sub, String name, String email) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("name", name);
        if (email != null) {
            attributes.put("email", email);
            attributes.put("email_verified", true);
        }
        return new GoogleOAuth2UserInfo(attributes);
    }

    private static KakaoOAuth2UserInfo kakao(long id, String nickname, String email) {
        Map<String, Object> account = new HashMap<>();
        account.put("profile", Map.of("nickname", nickname));
        if (email != null) {
            account.put("email", email);
        }
        return new KakaoOAuth2UserInfo(Map.of("id", id, "kakao_account", account));
    }
}

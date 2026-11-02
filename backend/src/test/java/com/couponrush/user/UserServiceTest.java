package com.couponrush.user;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.oauth2.userinfo.KakaoOAuth2UserInfo;
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

    private static KakaoOAuth2UserInfo kakao(long id, String nickname, String email) {
        Map<String, Object> account = new HashMap<>();
        account.put("profile", Map.of("nickname", nickname));
        if (email != null) {
            account.put("email", email);
        }
        return new KakaoOAuth2UserInfo(Map.of("id", id, "kakao_account", account));
    }
}

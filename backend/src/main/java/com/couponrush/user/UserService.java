package com.couponrush.user;

import com.couponrush.auth.oauth2.userinfo.OAuth2UserInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 소셜 로그인 사용자를 조회하고, 없으면 가입시킨다.
     * 제공자별 차이는 OAuth2UserInfo 가 흡수하므로 이 메서드는 제공자를 몰라도 된다.
     */
    @Transactional
    public User loginOrRegister(OAuth2UserInfo userInfo) {
        String email = userInfo.getEmail().orElse(null);

        return userRepository.findByProviderAndProviderId(userInfo.getProvider(), userInfo.getProviderId())
                .map(user -> {
                    user.updateProfile(userInfo.getNickname(), email);   // dirty checking 으로 반영
                    return user;
                })
                .orElseGet(() -> userRepository.save(User.createSocialUser(
                        userInfo.getProvider(), userInfo.getProviderId(), userInfo.getNickname(), email)));
    }
}

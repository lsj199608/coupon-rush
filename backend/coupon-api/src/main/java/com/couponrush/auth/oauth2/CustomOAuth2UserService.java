package com.couponrush.auth.oauth2;

import com.couponrush.auth.oauth2.userinfo.OAuth2UserInfo;
import com.couponrush.auth.oauth2.userinfo.OAuth2UserInfoFactory;
import com.couponrush.user.User;
import com.couponrush.user.UserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 제공자에서 사용자 정보를 받아온 뒤(super.loadUser) 우리 회원으로 가입/조회한다.
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserService userService;

    public CustomOAuth2UserService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo userInfo;
        try {
            userInfo = OAuth2UserInfoFactory.of(registrationId, oAuth2User.getAttributes());
        } catch (IllegalArgumentException e) {
            // 실패 핸들러로 흘려보내기 위해 OAuth2AuthenticationException 으로 변환
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"), e.getMessage(), e);
        }

        User user = userService.loginOrRegister(userInfo);
        return new LoginUser(user.getId(), user.getRole(), oAuth2User.getAttributes());
    }
}

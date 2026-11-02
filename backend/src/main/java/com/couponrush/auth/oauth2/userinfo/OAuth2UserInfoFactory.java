package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Map;

public final class OAuth2UserInfoFactory {

    private OAuth2UserInfoFactory() {
    }

    public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        OAuth2Provider provider = OAuth2Provider.fromRegistrationId(registrationId);
        return switch (provider) {
            case KAKAO -> new KakaoOAuth2UserInfo(attributes);
        };
    }
}

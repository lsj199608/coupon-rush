package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Map;

public final class OAuth2UserInfoFactory {

    private OAuth2UserInfoFactory() {
    }

    /** enum switch 라 제공자를 추가하고 분기를 빠뜨리면 컴파일 에러가 난다 */
    public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        OAuth2Provider provider = OAuth2Provider.fromRegistrationId(registrationId);
        return switch (provider) {
            case KAKAO -> new KakaoOAuth2UserInfo(attributes);
            case NAVER -> new NaverOAuth2UserInfo(attributes);
            case GOOGLE -> new GoogleOAuth2UserInfo(attributes);
        };
    }
}

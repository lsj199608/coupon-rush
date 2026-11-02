package com.couponrush.user;

import java.util.Arrays;

/**
 * 소셜 로그인 제공자. 값은 Spring Security 의 registrationId(application.yml 의 registration 키)와 매핑된다.
 */
public enum OAuth2Provider {
    KAKAO("kakao");
    // NAVER("naver"), GOOGLE("google") 은 추가 시 여기에

    private final String registrationId;

    OAuth2Provider(String registrationId) {
        this.registrationId = registrationId;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public static OAuth2Provider fromRegistrationId(String registrationId) {
        return Arrays.stream(values())
                .filter(p -> p.registrationId.equalsIgnoreCase(registrationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 소셜 로그인입니다: " + registrationId));
    }
}

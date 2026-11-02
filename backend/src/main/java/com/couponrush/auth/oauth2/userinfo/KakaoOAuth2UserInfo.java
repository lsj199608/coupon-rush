package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Map;
import java.util.Optional;

/**
 * 카카오 /v2/user/me 응답:
 * <pre>
 * {
 *   "id": 123456789,
 *   "kakao_account": {
 *     "profile": { "nickname": "홍길동" },
 *     "email": "a@b.com"            // 동의 + 비즈 앱일 때만
 *   }
 * }
 * </pre>
 */
public class KakaoOAuth2UserInfo implements OAuth2UserInfo {

    private static final int MAX_NICKNAME_LENGTH = 50;

    private final Map<String, Object> attributes;

    public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
        if (attributes == null || attributes.get("id") == null) {
            throw new IllegalArgumentException("카카오 사용자 정보에 id 가 없습니다.");
        }
        this.attributes = attributes;
    }

    @Override
    public OAuth2Provider getProvider() {
        return OAuth2Provider.KAKAO;
    }

    @Override
    public String getProviderId() {
        return String.valueOf(attributes.get("id"));
    }

    @Override
    public String getNickname() {
        return asString(profile().get("nickname"))
                .filter(s -> !s.isBlank())
                .map(s -> s.length() > MAX_NICKNAME_LENGTH ? s.substring(0, MAX_NICKNAME_LENGTH) : s)
                .orElse("kakao_" + getProviderId());
    }

    @Override
    public Optional<String> getEmail() {
        return asString(kakaoAccount().get("email")).filter(s -> !s.isBlank());
    }

    private Map<String, Object> kakaoAccount() {
        return asMap(attributes.get("kakao_account"));
    }

    private Map<String, Object> profile() {
        return asMap(kakaoAccount().get("profile"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Optional<String> asString(Object value) {
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }
}

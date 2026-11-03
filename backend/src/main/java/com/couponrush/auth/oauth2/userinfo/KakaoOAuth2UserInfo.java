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
public class KakaoOAuth2UserInfo extends AbstractOAuth2UserInfo {

    private final String id;
    private final Map<String, Object> kakaoAccount;

    public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
        Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
        this.id = requireId(attrs.get("id"), "카카오");
        this.kakaoAccount = asMap(attrs.get("kakao_account"));
    }

    @Override
    public OAuth2Provider getProvider() {
        return OAuth2Provider.KAKAO;
    }

    @Override
    public String getProviderId() {
        return id;
    }

    @Override
    protected Optional<String> rawNickname() {
        return asString(asMap(kakaoAccount.get("profile")).get("nickname"));
    }

    @Override
    protected Optional<String> rawEmail() {
        return asString(kakaoAccount.get("email"));
    }
}

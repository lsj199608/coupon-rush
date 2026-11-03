package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Map;
import java.util.Optional;

/**
 * 구글 userinfo(v3) 응답 — 평평한(flat) 구조:
 * <pre>
 * {
 *   "sub": "1098765432101234567",   // 구글 계정 고유 ID
 *   "name": "홍길동",
 *   "email": "a@gmail.com",
 *   "email_verified": true
 * }
 * </pre>
 */
public class GoogleOAuth2UserInfo extends AbstractOAuth2UserInfo {

    private final Map<String, Object> attributes;
    private final String id;

    public GoogleOAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes == null ? Map.of() : attributes;
        this.id = requireId(this.attributes.get("sub"), "구글");
    }

    @Override
    public OAuth2Provider getProvider() {
        return OAuth2Provider.GOOGLE;
    }

    @Override
    public String getProviderId() {
        return id;
    }

    @Override
    protected Optional<String> rawNickname() {
        return asString(attributes.get("name"));
    }

    /** 구글이 소유를 확인한 이메일만 신뢰한다 */
    @Override
    protected Optional<String> rawEmail() {
        if (!Boolean.TRUE.equals(attributes.get("email_verified"))) {
            return Optional.empty();
        }
        return asString(attributes.get("email"));
    }
}

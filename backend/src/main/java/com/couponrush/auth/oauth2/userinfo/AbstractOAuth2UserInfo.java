package com.couponrush.auth.oauth2.userinfo;

import java.util.Map;
import java.util.Optional;

/**
 * 제공자 공통 규칙을 한 곳에 둔다.
 * - 닉네임: 비어 있으면 "{provider}_{id}" 기본값, 컬럼 길이(50) 초과 시 자름
 * - 이메일: 비어 있으면 없는 것으로 취급
 * 구현체는 "응답의 어디에서 값을 꺼내는지"만 정의하면 된다.
 */
public abstract class AbstractOAuth2UserInfo implements OAuth2UserInfo {

    private static final int MAX_NICKNAME_LENGTH = 50;

    /** 응답에서 꺼낸 원본 닉네임 (없으면 empty) */
    protected abstract Optional<String> rawNickname();

    /** 응답에서 꺼낸 원본 이메일 (없거나 신뢰할 수 없으면 empty) */
    protected abstract Optional<String> rawEmail();

    @Override
    public final String getNickname() {
        return rawNickname()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.length() > MAX_NICKNAME_LENGTH ? s.substring(0, MAX_NICKNAME_LENGTH) : s)
                .orElse(getProvider().getRegistrationId() + "_" + getProviderId());
    }

    @Override
    public final Optional<String> getEmail() {
        return rawEmail().map(String::strip).filter(s -> !s.isEmpty());
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    protected static Optional<String> asString(Object value) {
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** 제공자마다 ID 가 숫자/문자열로 다르게 오므로 문자열로 통일 */
    protected static String requireId(Object id, String providerName) {
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException(providerName + " 사용자 정보에 id 가 없습니다.");
        }
        return String.valueOf(id);
    }
}

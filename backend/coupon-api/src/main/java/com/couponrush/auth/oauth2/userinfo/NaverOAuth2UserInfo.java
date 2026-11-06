package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Map;
import java.util.Optional;

/**
 * 네이버 /v1/nid/me 응답 — 실제 사용자 정보가 response 안에 중첩되어 있다:
 * <pre>
 * {
 *   "resultcode": "00",
 *   "message": "success",
 *   "response": {
 *     "id": "abcdEFGH1234...",      // 앱별 고유 문자열 ID
 *     "nickname": "홍길동",          // 동의 시
 *     "name": "홍길동",              // 동의 시
 *     "email": "a@naver.com"        // 동의 시
 *   }
 * }
 * </pre>
 * Spring 이 넘겨주는 attributes 는 바깥 전체 맵이므로 여기서 response 를 한 번 벗겨낸다.
 */
public class NaverOAuth2UserInfo extends AbstractOAuth2UserInfo {

    private final Map<String, Object> response;
    private final String id;

    public NaverOAuth2UserInfo(Map<String, Object> attributes) {
        this.response = asMap(attributes == null ? null : attributes.get("response"));
        this.id = requireId(response.get("id"), "네이버");
    }

    @Override
    public OAuth2Provider getProvider() {
        return OAuth2Provider.NAVER;
    }

    @Override
    public String getProviderId() {
        return id;
    }

    /** 별명 동의가 없으면 이름으로 대체 */
    @Override
    protected Optional<String> rawNickname() {
        return asString(response.get("nickname"))
                .filter(s -> !s.isBlank())
                .or(() -> asString(response.get("name")));
    }

    @Override
    protected Optional<String> rawEmail() {
        return asString(response.get("email"));
    }
}

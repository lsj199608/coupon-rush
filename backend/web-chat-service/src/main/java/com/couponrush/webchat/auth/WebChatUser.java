package com.couponrush.webchat.auth;

import java.security.Principal;

/**
 * 연결 인증으로 확정된 회원. STOMP 세션의 Principal 이 되어, 이후 그 연결의 모든 메시지에 서버가 붙인다.
 * 메시지 바디로는 바꿀 수 없다 (보낸 사람 = 연결한 사람).
 *
 * @param userId   coupon-api 회원 ID
 * @param nickname 연결 시점 닉네임 (coupon-api users.nickname 과 같은 50자 상한)
 */
public record WebChatUser(long userId, String nickname) implements Principal {

    public static final int NICKNAME_MAX = 50;

    public WebChatUser {
        if (userId < 1) {
            throw new IllegalArgumentException("userId 는 1 이상이어야 합니다");
        }
        if (nickname == null || nickname.isBlank() || nickname.length() > NICKNAME_MAX) {
            throw new IllegalArgumentException("nickname 은 1~" + NICKNAME_MAX + "자여야 합니다");
        }
    }

    /** 사용자 주소(/user/queue/errors)를 회원 ID 로 찾는다. */
    @Override
    public String getName() {
        return Long.toString(userId);
    }
}

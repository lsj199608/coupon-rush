package com.couponrush.webchat.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * Redis 에 저장하는 티켓 내용 (값 = 이 레코드의 JSON). 키는 {@link WebChatTickets#redisKey(String)}.
 * <p>
 * 보낸 사람은 연결 시점에 이 값으로 정해진다 — web-chat-service 는 JWT 비밀키도, users 테이블도 모른다.
 * 필드는 추가만 한다 (먼저 배포된 쪽이 모르는 필드는 무시).
 *
 * @param userId   coupon-api 회원 ID
 * @param nickname 발급 시점 닉네임 (coupon-api users.nickname, 50자)
 * @param issuedAt 발급 시각 (기록·로그용 — 만료는 Redis TTL 이 정한다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebChatTicket(long userId, String nickname, Instant issuedAt) {

    public static final int NICKNAME_MAX = 50;

    public WebChatTicket {
        if (userId < 1) {
            throw new IllegalArgumentException("userId 는 1 이상이어야 합니다");
        }
        if (nickname == null || nickname.isBlank() || nickname.length() > NICKNAME_MAX) {
            throw new IllegalArgumentException("nickname 은 1~" + NICKNAME_MAX + "자여야 합니다");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}

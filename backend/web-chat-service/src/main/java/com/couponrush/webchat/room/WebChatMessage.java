package com.couponrush.webchat.room;

import java.time.Instant;
import java.util.Objects;

/**
 * 방 구독자에게 나가는 메시지. 보낸 사람·시각·ID 는 전부 서버가 채운다 — 클라이언트 바디의 같은 이름 필드는 읽지 않는다(사칭 방지).
 *
 * @param messageId      서버가 만든 UUID (화면 key, D3 여러 대에서 중복 표시 방지용)
 * @param eventId        방 = 이벤트 ID
 * @param senderId       보낸 회원 ID (연결 인증에서 정해짐)
 * @param senderNickname 보낸 회원 닉네임 (연결 시점 값)
 * @param content        앞뒤 공백을 뺀 본문, 1~{@value #CONTENT_MAX}자. 화면은 글자로만 그린다 (HTML 해석 금지)
 * @param sentAt         서버가 받은 시각
 */
public record WebChatMessage(String messageId, long eventId, long senderId, String senderNickname,
                          String content, Instant sentAt) {

    /** 본문 상한 (글자 = 코드 포인트 — 이모지 하나를 두 글자로 세지 않게). */
    public static final int CONTENT_MAX = 500;

    public WebChatMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(senderNickname);
        Objects.requireNonNull(content);
        Objects.requireNonNull(sentAt);
    }
}

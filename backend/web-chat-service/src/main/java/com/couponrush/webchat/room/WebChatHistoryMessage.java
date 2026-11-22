package com.couponrush.webchat.room;

import java.time.Instant;

/**
 * 저장된 메시지 (지난 대화 조회 응답). 실시간 {@link WebChatMessage} 와 같은 필드 + 저장 순서 id.
 *
 * @param id 이전 페이지를 부를 때 쓰는 커서 (beforeId). 실시간 메시지에는 없다 — 방에 먼저 보내고 나중에 저장하므로
 */
public record WebChatHistoryMessage(long id, String messageId, long eventId, long senderId, String senderNickname,
                                    String content, Instant sentAt) {
}

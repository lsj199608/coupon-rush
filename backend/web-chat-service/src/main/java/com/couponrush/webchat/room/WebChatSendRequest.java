package com.couponrush.webchat.room;

/**
 * 클라이언트가 보내는 바디. 본문만 받는다 — senderId 같은 다른 필드가 와도 무시된다 (Boot ObjectMapper: 모르는 필드 무시).
 * 본문 규칙(공백·길이)은 {@link WebChatService} 가 검사한다.
 */
public record WebChatSendRequest(String content) {
}

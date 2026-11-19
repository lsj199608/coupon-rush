package com.couponrush.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 대화방 + 턴(오래된 것부터). 턴은 최근 {@value com.couponrush.chat.ChatService#TURNS_SHOWN}개까지만 —
 * 그보다 오래된 턴은 응답에서 빠진다 (화면 복원에는 충분하고, 응답 크기가 대화 길이에 비례해 커지지 않게).
 */
public record ChatSessionResponse(Long sessionId, Instant createdAt, Instant lastMessageAt, List<ChatTurnResponse> turns) {
}

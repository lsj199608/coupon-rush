package com.couponrush.chatbot;

import java.time.Instant;

/** 대화방 목록 한 줄. firstQuestion = 첫 질문 (아직 질문이 없으면 null) */
public record ChatbotSessionSummary(Long id, Instant createdAt, Instant lastMessageAt, String firstQuestion) {
}

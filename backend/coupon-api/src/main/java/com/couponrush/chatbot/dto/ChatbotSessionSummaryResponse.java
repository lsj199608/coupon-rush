package com.couponrush.chatbot.dto;

import com.couponrush.chatbot.ChatbotSessionSummary;

import java.time.Instant;

/** 내 대화방 목록 한 줄. title = 첫 질문 앞부분 (질문이 없으면 null — 화면이 "새 대화"로 표시) */
public record ChatbotSessionSummaryResponse(Long sessionId, String title, Instant createdAt, Instant lastMessageAt) {

    static final int TITLE_MAX = 30;

    public static ChatbotSessionSummaryResponse of(ChatbotSessionSummary summary) {
        String question = summary.firstQuestion() == null ? null : summary.firstQuestion().replaceAll("\\s+", " ");
        String title = question == null ? null
                : question.length() <= TITLE_MAX ? question : question.substring(0, TITLE_MAX) + "…";
        return new ChatbotSessionSummaryResponse(summary.id(), title, summary.createdAt(), summary.lastMessageAt());
    }
}

package com.couponrush.chat.dto;

import com.couponrush.chat.ChatSessionSummary;

import java.time.Instant;

/** 내 대화방 목록 한 줄. title = 첫 질문 앞부분 (질문이 없으면 null — 화면이 "새 대화"로 표시) */
public record ChatSessionSummaryResponse(Long sessionId, String title, Instant createdAt, Instant lastMessageAt) {

    static final int TITLE_MAX = 30;

    public static ChatSessionSummaryResponse of(ChatSessionSummary summary) {
        String question = summary.firstQuestion() == null ? null : summary.firstQuestion().replaceAll("\\s+", " ");
        String title = question == null ? null
                : question.length() <= TITLE_MAX ? question : question.substring(0, TITLE_MAX) + "…";
        return new ChatSessionSummaryResponse(summary.id(), title, summary.createdAt(), summary.lastMessageAt());
    }
}

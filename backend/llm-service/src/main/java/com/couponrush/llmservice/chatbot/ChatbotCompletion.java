package com.couponrush.llmservice.chatbot;

import com.couponrush.llm.contract.ChatbotReply;

import java.util.Objects;

/** 챗봇 답변과 사용 토큰 (도구를 여러 번 부른 경우 모든 LLM 호출의 합) */
public record ChatbotCompletion(ChatbotReply reply, String model, int inputTokens, int outputTokens) {

    public ChatbotCompletion {
        Objects.requireNonNull(reply, "reply");
    }
}

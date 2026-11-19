package com.couponrush.llmservice.chat;

import com.couponrush.llm.contract.ChatReply;

import java.util.Objects;

/** 챗봇 답변과 사용 토큰 (도구를 여러 번 부른 경우 모든 LLM 호출의 합) */
public record ChatCompletion(ChatReply reply, String model, int inputTokens, int outputTokens) {

    public ChatCompletion {
        Objects.requireNonNull(reply, "reply");
    }
}

package com.couponrush.chatbot.dto;

import com.couponrush.llm.contract.ChatbotInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/chatbot/sessions/{id}/turns 바디. 길이 상한은 계약(ChatbotInput.MESSAGE_MAX)과 같다 */
public record ChatbotMessageRequest(
        @NotBlank @Size(max = ChatbotInput.MESSAGE_MAX) String message) {

    /** 앞뒤 공백만 제거 — 줄바꿈은 질문의 일부일 수 있어 그대로 둔다 */
    public String normalizedMessage() {
        return message.strip();
    }
}

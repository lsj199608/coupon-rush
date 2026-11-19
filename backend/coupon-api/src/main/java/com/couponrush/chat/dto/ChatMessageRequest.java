package com.couponrush.chat.dto;

import com.couponrush.llm.contract.ChatInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/chat/sessions/{id}/turns 바디. 길이 상한은 계약(ChatInput.MESSAGE_MAX)과 같다 */
public record ChatMessageRequest(
        @NotBlank @Size(max = ChatInput.MESSAGE_MAX) String message) {

    /** 앞뒤 공백만 제거 — 줄바꿈은 질문의 일부일 수 있어 그대로 둔다 */
    public String normalizedMessage() {
        return message.strip();
    }
}

package com.couponrush.llmservice.chatbot.tool;

/**
 * 도구 호출 결과 — 모델에게 그대로 돌려준다.
 *
 * @param callId  {@link ToolCall#id()}
 * @param text    결과 본문 (도구 서버의 JSON 문자열, 또는 실패 설명)
 * @param isError 실패면 true — 모델이 읽고 사용자에게 설명하거나 다른 방법을 고른다
 */
public record ToolOutcome(String callId, String text, boolean isError) {

    public static ToolOutcome error(String callId, String text) {
        return new ToolOutcome(callId, text, true);
    }
}

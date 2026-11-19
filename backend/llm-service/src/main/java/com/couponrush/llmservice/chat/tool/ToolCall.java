package com.couponrush.llmservice.chat.tool;

import java.util.Map;

/**
 * 모델이 요청한 도구 호출 하나.
 *
 * @param id        모델 쪽 호출 ID (결과를 짝지어 돌려줄 때 쓴다)
 * @param name      도구 이름
 * @param arguments 인자 (JSON 객체)
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

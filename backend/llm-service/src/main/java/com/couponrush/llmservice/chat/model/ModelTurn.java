package com.couponrush.llmservice.chat.model;

import com.couponrush.llmservice.chat.tool.ToolCall;

import java.util.List;

/** 모델 호출 한 번의 결과: 답 또는 도구 호출 요청. 토큰은 이 호출 한 번 분 */
public sealed interface ModelTurn {

    String model();

    int inputTokens();

    int outputTokens();

    record Answer(String text, String model, int inputTokens, int outputTokens) implements ModelTurn {
    }

    record ToolRequests(List<ToolCall> calls, String model, int inputTokens, int outputTokens) implements ModelTurn {

        public ToolRequests {
            calls = List.copyOf(calls);
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("도구 호출이 하나 이상 있어야 합니다");
            }
        }
    }
}

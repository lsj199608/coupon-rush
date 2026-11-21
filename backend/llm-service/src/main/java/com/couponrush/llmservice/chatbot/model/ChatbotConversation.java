package com.couponrush.llmservice.chatbot.model;

import com.couponrush.llmservice.chatbot.tool.ToolOutcome;

import java.util.List;

/**
 * 진행 중인 대화 (질문 하나 동안). 모델 쪽 대화 기록은 구현이 들고 있다 —
 * Anthropic 은 응답 메시지를 <b>그대로</b>(생각 블록 포함) 이어 붙여야 해서, 공통 형식으로 바꿨다가 되돌리면 안 된다.
 */
public interface ChatbotConversation {

    /**
     * 모델을 한 번 부른다.
     *
     * @param toolsAllowed false 면 도구 없이 답만 하게 한다 (도구 호출 수·토큰 상한에 닿았을 때)
     * @throws com.couponrush.llmservice.client.LlmCallException 호출 실패·거절·잘림
     */
    ModelTurn next(boolean toolsAllowed);

    /** 직전 {@link ModelTurn.ToolRequests} 의 모든 호출에 대한 결과 (호출 하나도 빠짐없이, 한 번에) */
    void addToolResults(List<ToolOutcome> results);
}

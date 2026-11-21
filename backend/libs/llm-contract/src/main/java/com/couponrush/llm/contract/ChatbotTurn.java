package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * 챗봇 대화의 지난 한 턴 (사용자 질문 + 챗봇 답변). 다음 질문을 보낼 때 문맥으로 함께 보낸다 ({@link ChatbotInput#history()}).
 * 답을 받지 못한 턴(실패·대기 중)은 보내지 않는다 — 답 없는 질문은 모델에게 문맥이 아니라 혼란이다.
 *
 * @param question 사용자 질문 (1~{@value ChatbotInput#MESSAGE_MAX}자)
 * @param answer   챗봇 답변 (1~{@value ChatbotReply#TEXT_MAX}자)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatbotTurn(String question, String answer) {

    public ChatbotTurn {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        if (question.isBlank() || question.length() > ChatbotInput.MESSAGE_MAX) {
            throw new IllegalArgumentException("question 은 1~" + ChatbotInput.MESSAGE_MAX + "자여야 합니다");
        }
        if (answer.isBlank() || answer.length() > ChatbotReply.TEXT_MAX) {
            throw new IllegalArgumentException("answer 는 1~" + ChatbotReply.TEXT_MAX + "자여야 합니다");
        }
    }
}

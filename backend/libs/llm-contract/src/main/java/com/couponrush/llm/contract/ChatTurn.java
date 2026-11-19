package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * 챗봇 대화의 지난 한 턴 (사용자 질문 + 챗봇 답변). 다음 질문을 보낼 때 문맥으로 함께 보낸다 ({@link ChatInput#history()}).
 * 답을 받지 못한 턴(실패·대기 중)은 보내지 않는다 — 답 없는 질문은 모델에게 문맥이 아니라 혼란이다.
 *
 * @param question 사용자 질문 (1~{@value ChatInput#MESSAGE_MAX}자)
 * @param answer   챗봇 답변 (1~{@value ChatReply#TEXT_MAX}자)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatTurn(String question, String answer) {

    public ChatTurn {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        if (question.isBlank() || question.length() > ChatInput.MESSAGE_MAX) {
            throw new IllegalArgumentException("question 은 1~" + ChatInput.MESSAGE_MAX + "자여야 합니다");
        }
        if (answer.isBlank() || answer.length() > ChatReply.TEXT_MAX) {
            throw new IllegalArgumentException("answer 는 1~" + ChatReply.TEXT_MAX + "자여야 합니다");
        }
    }
}

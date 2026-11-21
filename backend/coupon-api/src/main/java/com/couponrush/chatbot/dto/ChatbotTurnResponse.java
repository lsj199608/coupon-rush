package com.couponrush.chatbot.dto;

import com.couponrush.chatbot.ChatbotTurn;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestStatus;
import com.couponrush.llm.contract.LlmFailureReason;

import java.time.Instant;

/**
 * 대화의 한 턴. 화면은 status 로 말풍선을 그린다: PENDING = "답을 쓰는 중", COMPLETED = answer, FAILED = 사유 + (retryable 이면) [다시 보내기].
 *
 * @param answer     COMPLETED 일 때만
 * @param retryable  FAILED 일 때 같은 질문을 다시 보낼 의미가 있는가 (그 외 null) — 다시 보내기는 새 턴이다
 * @param deadlineAt 이 시각까지 답이 없으면 FAILED(TIMEOUT). 화면 폴링 상한의 기준
 * @param answeredAt 답(또는 실패)이 확정된 시각
 */
public record ChatbotTurnResponse(
        Long turnId,
        String question,
        LlmRequestStatus status,
        String answer,
        LlmFailureReason failureReason,
        Boolean retryable,
        Instant createdAt,
        Instant deadlineAt,
        Instant answeredAt) {

    public static ChatbotTurnResponse of(ChatbotTurn turn, LlmRequest request, String answer) {
        LlmFailureReason reason = request.getFailureReason();
        return new ChatbotTurnResponse(turn.getId(), turn.getQuestion(), request.getStatus(), answer,
                reason, reason == null ? null : reason.isRetryable(),
                turn.getCreatedAt(), request.getDeadlineAt(), request.getCompletedAt());
    }
}

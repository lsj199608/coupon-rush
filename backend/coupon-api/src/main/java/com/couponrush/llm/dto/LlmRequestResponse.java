package com.couponrush.llm.dto;

import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestStatus;
import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/admin/llm/requests/{id} 응답.
 *
 * @param candidates 생성 후보 (COMPLETED 일 때만, 그 외 빈 목록). 저장되지 않은 제안이다 — 관리자가 골라 폼에서 고친 뒤 저장한다
 * @param retryable  FAILED 일 때 [다시 시도]를 보여 줄지 (그 외 null)
 * @param cached     같은 입력의 이전 결과를 재사용했는가 (LLM 호출 0회)
 */
public record LlmRequestResponse(
        UUID requestId,
        LlmRequestType type,
        LlmRequestStatus status,
        List<EventCopyCandidate> candidates,
        LlmFailureReason failureReason,
        Boolean retryable,
        String model,
        int inputTokens,
        int outputTokens,
        boolean cached,
        Instant createdAt,
        Instant deadlineAt,
        Instant completedAt) {

    public static LlmRequestResponse of(LlmRequest request, List<EventCopyCandidate> candidates) {
        LlmFailureReason reason = request.getFailureReason();
        return new LlmRequestResponse(request.getRequestId(), request.getType(), request.getStatus(), candidates,
                reason, reason == null ? null : reason.isRetryable(), request.getModel(),
                request.getInputTokens(), request.getOutputTokens(), request.isCached(),
                request.getCreatedAt(), request.getDeadlineAt(), request.getCompletedAt());
    }
}

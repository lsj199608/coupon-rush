package com.couponrush.llm.dto;

import com.couponrush.llm.LlmRequestStatus;

import java.time.Instant;
import java.util.UUID;

/** 202 응답. 결과는 Location(GET /api/admin/llm/requests/{requestId})을 폴링한다 */
public record LlmRequestAcceptedResponse(UUID requestId, LlmRequestStatus status, Instant deadlineAt) {

    public static LlmRequestAcceptedResponse pending(UUID requestId, Instant deadlineAt) {
        return new LlmRequestAcceptedResponse(requestId, LlmRequestStatus.PENDING, deadlineAt);
    }
}

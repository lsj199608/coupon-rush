package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * coupon-api → llm-service 요청 ({@link LlmTopics#REQUESTS}, 키 = requestId).
 * <p>
 * 사용자 JWT 는 싣지 않는다. 인증·권한(ROLE_ADMIN)·레이트 리밋은 coupon-api 에서 끝났고,
 * llm-service 는 Kafka(내부망)를 신뢰 경계로 보고 메시지를 그대로 믿는다. requestedBy 는 감사·집계용.
 *
 * @param schemaVersion {@link LlmTopics#SCHEMA_VERSION}
 * @param requestId     coupon-api 가 만든 UUID — 멱등 처리, 결과 매칭, 상태 조회의 기준
 * @param type          기능 종류. 입력 필드는 type 에 맞는 것 하나만 채운다
 * @param requestedBy   요청한 회원 ID
 * @param requestedAt   접수 시각
 * @param deadline      이 시각이 지나면 coupon-api 는 TIMEOUT 으로 처리한다.
 *                      llm-service 는 지난 요청의 LLM 호출을 건너뛴다 (기다리는 사람이 없는데 비용을 쓰지 않음)
 * @param regenerate    true 면 응답 캐시를 건너뛰고 새로 생성 ("다시 생성" 버튼)
 * @param eventCopy     type = EVENT_COPY 일 때의 입력
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmRequestMessage(
        int schemaVersion,
        UUID requestId,
        LlmRequestType type,
        long requestedBy,
        Instant requestedAt,
        Instant deadline,
        boolean regenerate,
        EventCopyInput eventCopy
) {

    public LlmRequestMessage {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(deadline, "deadline");
        if (!deadline.isAfter(requestedAt)) {
            throw new IllegalArgumentException("deadline 은 requestedAt 보다 뒤여야 합니다");
        }
        if (type == LlmRequestType.EVENT_COPY && eventCopy == null) {
            throw new IllegalArgumentException("EVENT_COPY 요청에는 eventCopy 입력이 필요합니다");
        }
    }

    /** 이벤트 문구 생성 요청 (현재 버전) */
    public static LlmRequestMessage eventCopy(UUID requestId, long requestedBy, Instant requestedAt, Instant deadline,
                                              boolean regenerate, EventCopyInput input) {
        return new LlmRequestMessage(LlmTopics.SCHEMA_VERSION, requestId, LlmRequestType.EVENT_COPY,
                requestedBy, requestedAt, deadline, regenerate, input);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(deadline);
    }
}

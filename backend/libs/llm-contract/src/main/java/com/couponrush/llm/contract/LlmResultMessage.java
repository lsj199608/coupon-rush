package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * llm-service → coupon-api 결과 ({@link LlmTopics#RESULTS}, 키 = requestId).
 * <p>
 * 같은 requestId 결과가 두 번 오거나(재전달) TIMEOUT 뒤에 와도 된다 — coupon-api 는 PENDING 일 때만 반영한다
 * (조건부 UPDATE). 그래서 이 메시지에는 "몇 번째 시도인지" 같은 순서 정보가 필요 없다.
 *
 * @param schemaVersion {@link LlmTopics#SCHEMA_VERSION}
 * @param requestId     요청의 requestId
 * @param status        COMPLETED 면 candidates 가 1개 이상, FAILED 면 failureReason 이 있다
 * @param candidates    생성된 후보 (FAILED 면 빈 목록)
 * @param failureReason FAILED 사유 (COMPLETED 면 null)
 * @param model         실제로 호출한 모델 이름 (fake 제공자면 "fake", LLM 을 부르기 전에 실패했으면 null)
 * @param inputTokens   이 요청에 쓴 입력 토큰 (캐시 응답·호출 전 실패면 0) — 비용 추적
 * @param outputTokens  출력 토큰
 * @param cached        응답 캐시에서 꺼낸 결과인가 (LLM 호출 0회)
 * @param latencyMs     llm-service 가 메시지를 받고 결과를 만들기까지 걸린 시간
 * @param completedAt   결과를 만든 시각
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmResultMessage(
        int schemaVersion,
        UUID requestId,
        LlmResultStatus status,
        List<EventCopyCandidate> candidates,
        LlmFailureReason failureReason,
        String model,
        int inputTokens,
        int outputTokens,
        boolean cached,
        long latencyMs,
        Instant completedAt
) {

    /** 한 번에 만드는 후보 수 */
    public static final int CANDIDATE_COUNT = 3;

    public LlmResultMessage {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completedAt, "completedAt");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (inputTokens < 0 || outputTokens < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("토큰 수·지연 시간은 0 이상이어야 합니다");
        }
        switch (status) {
            case COMPLETED -> {
                if (candidates.isEmpty() || candidates.size() > CANDIDATE_COUNT) {
                    throw new IllegalArgumentException("COMPLETED 결과는 후보가 1~" + CANDIDATE_COUNT + "개여야 합니다");
                }
                if (failureReason != null) {
                    throw new IllegalArgumentException("COMPLETED 결과에는 failureReason 이 없어야 합니다");
                }
            }
            case FAILED -> {
                if (failureReason == null) {
                    throw new IllegalArgumentException("FAILED 결과에는 failureReason 이 필요합니다");
                }
                if (!candidates.isEmpty()) {
                    throw new IllegalArgumentException("FAILED 결과에는 후보가 없어야 합니다");
                }
            }
        }
    }

    public static LlmResultMessage completed(UUID requestId, List<EventCopyCandidate> candidates, String model,
                                             int inputTokens, int outputTokens, boolean cached,
                                             long latencyMs, Instant completedAt) {
        return new LlmResultMessage(LlmTopics.SCHEMA_VERSION, requestId, LlmResultStatus.COMPLETED, candidates, null,
                model, inputTokens, outputTokens, cached, latencyMs, completedAt);
    }

    /** 실패. LLM 을 이미 호출한 뒤 실패했다면(예: INVALID_OUTPUT) 쓴 토큰도 함께 보낸다 — 비용은 이미 나갔으므로 */
    public static LlmResultMessage failed(UUID requestId, LlmFailureReason reason, String model,
                                          int inputTokens, int outputTokens, long latencyMs, Instant completedAt) {
        return new LlmResultMessage(LlmTopics.SCHEMA_VERSION, requestId, LlmResultStatus.FAILED, List.of(), reason,
                model, inputTokens, outputTokens, false, latencyMs, completedAt);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}

package com.couponrush.llm;

import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * llm-results 메시지를 llm_requests 에 반영한다. PENDING 일 때만 바뀐다 (조건부 UPDATE) —
 * 같은 결과가 두 번 오거나, TIMEOUT 뒤에 오거나, 모르는 requestId 여도 예외 없이 무시한다 (재시도해도 결과가 같으므로).
 */
@Component
public class LlmResultHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmResultHandler.class);

    /** 결과 반영 결과 (테스트·로그용) */
    public enum Outcome { APPLIED, IGNORED_ALREADY_FINISHED, IGNORED_UNKNOWN_REQUEST, IGNORED_UNSUPPORTED_VERSION }

    private final LlmRequestRepository repository;
    private final ObjectMapper objectMapper;

    public LlmResultHandler(LlmRequestRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Outcome handle(LlmResultMessage message) {
        if (!LlmTopics.isSupported(message.schemaVersion())) {
            log.warn("지원하지 않는 LLM 결과 버전 — 무시: requestId={}, schemaVersion={}", message.requestId(), message.schemaVersion());
            return Outcome.IGNORED_UNSUPPORTED_VERSION;
        }

        int updated = switch (message.status()) {
            case COMPLETED -> repository.complete(message.requestId(), toJson(message), message.model(),
                    message.inputTokens(), message.outputTokens(), message.cached(), message.completedAt());
            case FAILED -> repository.fail(message.requestId(), message.failureReason().name(), message.model(),
                    message.inputTokens(), message.outputTokens(), message.completedAt());
        };
        if (updated == 1) {
            return Outcome.APPLIED;
        }

        return repository.findById(message.requestId())
                .map(existing -> {
                    // 비용은 이미 나갔다 (TIMEOUT 뒤 도착이 잦으면 마감·모델 지연을 조정해야 한다는 신호)
                    log.warn("이미 끝난 LLM 요청의 결과 — 무시: requestId={}, 현재={}, 도착={}, tokens={}",
                            message.requestId(), existing.getStatus(), message.status(), message.totalTokens());
                    return Outcome.IGNORED_ALREADY_FINISHED;
                })
                .orElseGet(() -> {
                    log.warn("알 수 없는 LLM 요청의 결과 — 무시: requestId={}", message.requestId());
                    return Outcome.IGNORED_UNKNOWN_REQUEST;
                });
    }

    /** result 컬럼 내용: 문구 생성 = 후보 목록, 검색 = 검색 조건 (계약상 COMPLETED 는 둘 중 정확히 하나만 있다) */
    private String toJson(LlmResultMessage message) {
        try {
            return objectMapper.writeValueAsString(
                    message.searchFilter() != null ? message.searchFilter() : message.candidates());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM 결과 직렬화 실패: requestId=" + message.requestId(), e);
        }
    }
}

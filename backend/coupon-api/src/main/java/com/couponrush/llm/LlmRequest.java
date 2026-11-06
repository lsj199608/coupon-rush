package com.couponrush.llm;

import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * LLM 요청 한 건 (V5 llm_requests). 생성 시 PENDING 으로 INSERT 하고,
 * 이후 상태 변경은 엔티티가 아니라 {@link LlmRequestRepository} 의 조건부 UPDATE 로만 한다
 * (결과 컨슈머·타임아웃 정리·조회 시 만료 처리가 동시에 일어나도 먼저 온 하나만 반영되게).
 * <p>
 * input / result 는 JSON 문자열 그대로 둔다 — 직렬화는 Spring Boot ObjectMapper 로 (Kafka 메시지와 같은 형식).
 */
@Entity
@Table(name = "llm_requests")
public class LlmRequest implements Persistable<UUID> {

    @Id
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LlmRequestType type;

    @Column(nullable = false)
    private Long requestedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String input;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LlmRequestStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private String result;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private LlmFailureReason failureReason;

    @Column(length = 100)
    private String model;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private boolean cached;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant deadlineAt;

    private Instant completedAt;

    @Transient
    private boolean isNew = true;

    protected LlmRequest() {
    }

    public static LlmRequest pending(UUID requestId, LlmRequestType type, Long requestedBy, String inputJson,
                                     Instant createdAt, Instant deadlineAt) {
        LlmRequest request = new LlmRequest();
        request.requestId = requestId;
        request.type = type;
        request.requestedBy = requestedBy;
        request.input = inputJson;
        request.status = LlmRequestStatus.PENDING;
        request.createdAt = createdAt;
        request.deadlineAt = deadlineAt;
        return request;
    }

    /** 결과는 아직 없지만 마감이 지났다 → 조회 시 TIMEOUT 으로 확정할 대상 */
    public boolean isExpiredPending(Instant now) {
        return status == LlmRequestStatus.PENDING && !now.isBefore(deadlineAt);
    }

    public boolean isRequestedBy(Long userId) {
        return requestedBy.equals(userId);
    }

    @Override
    public UUID getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getRequestId() { return requestId; }
    public LlmRequestType getType() { return type; }
    public Long getRequestedBy() { return requestedBy; }
    public String getInput() { return input; }
    public LlmRequestStatus getStatus() { return status; }
    public String getResult() { return result; }
    public LlmFailureReason getFailureReason() { return failureReason; }
    public String getModel() { return model; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public boolean isCached() { return cached; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getCompletedAt() { return completedAt; }
}

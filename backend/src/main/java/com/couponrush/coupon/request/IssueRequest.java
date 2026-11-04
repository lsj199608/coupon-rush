package com.couponrush.coupon.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 비동기 발급 요청의 최종 결과. 컨슈머가 COMPLETED / FAILED 로 기록한다.
 * ID(requestId)는 API 가 발급하는 UUID 라서 Persistable 로 "새 엔티티"임을 알려 준다
 * (그렇지 않으면 save 가 INSERT 대신 SELECT 후 merge 를 시도한다).
 */
@Entity
@Table(name = "issue_requests")
public class IssueRequest implements Persistable<String> {

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssueRequestStatus status;

    private Long couponIssueId;

    @Column(length = 50)
    private String failureReason;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant processedAt;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    protected IssueRequest() {
    }

    private IssueRequest(String requestId, Long couponId, Long userId, IssueRequestStatus status,
                         Long couponIssueId, String failureReason, Instant requestedAt, Instant processedAt) {
        this.requestId = requestId;
        this.couponId = couponId;
        this.userId = userId;
        this.status = status;
        this.couponIssueId = couponIssueId;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.processedAt = processedAt;
    }

    public static IssueRequest completed(String requestId, Long couponId, Long userId, Long couponIssueId,
                                         Instant requestedAt, Instant processedAt) {
        return new IssueRequest(requestId, couponId, userId, IssueRequestStatus.COMPLETED,
                couponIssueId, null, requestedAt, processedAt);
    }

    public static IssueRequest failed(String requestId, Long couponId, Long userId, String failureReason,
                                      Instant requestedAt, Instant processedAt) {
        return new IssueRequest(requestId, couponId, userId, IssueRequestStatus.FAILED,
                null, failureReason, requestedAt, processedAt);
    }

    @Override
    public String getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public String getRequestId() { return requestId; }
    public Long getCouponId() { return couponId; }
    public Long getUserId() { return userId; }
    public IssueRequestStatus getStatus() { return status; }
    public Long getCouponIssueId() { return couponIssueId; }
    public String getFailureReason() { return failureReason; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getProcessedAt() { return processedAt; }
}

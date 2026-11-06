package com.couponrush.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    public static final int DESCRIPTION_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 이벤트 설명 (V6, 선택). 최대 500자 — LLM 문구 후보 길이(EventCopyCandidate.DESCRIPTION_MAX)와 같다 */
    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Column(nullable = false)
    private int totalQuantity;

    @Column(nullable = false)
    private int issuedQuantity;

    @Column(nullable = false)
    private Instant issueStartAt;

    @Column(nullable = false)
    private Instant issueEndAt;

    /** 낙관적 락. dirty checking UPDATE 에 "WHERE version = ?" 가 붙고, 0행이면 충돌 예외 */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Coupon() {
    }

    private Coupon(String name, String description, int totalQuantity, Instant issueStartAt, Instant issueEndAt) {
        this.name = name;
        this.description = description;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
    }

    public static Coupon create(String name, int totalQuantity, Instant issueStartAt, Instant issueEndAt) {
        return create(name, null, totalQuantity, issueStartAt, issueEndAt);
    }

    /** @param description 선택. 공백만 있으면 없음(null)으로 저장 */
    public static Coupon create(String name, String description, int totalQuantity, Instant issueStartAt, Instant issueEndAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("쿠폰 이름은 필수입니다.");
        }
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("총 수량은 1 이상이어야 합니다.");
        }
        if (issueStartAt == null || issueEndAt == null || !issueStartAt.isBefore(issueEndAt)) {
            throw new IllegalArgumentException("발급 시작 시각은 종료 시각보다 앞서야 합니다.");
        }
        String normalizedDescription = description == null || description.isBlank() ? null : description.strip();
        if (normalizedDescription != null && normalizedDescription.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("설명은 " + DESCRIPTION_MAX + "자 이하여야 합니다.");
        }
        return new Coupon(name.strip(), normalizedDescription, totalQuantity, issueStartAt, issueEndAt);
    }

    /** 시작 시각 포함, 종료 시각 미포함 [start, end) */
    public boolean isInIssuePeriod(Instant now) {
        return !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }

    public boolean isSoldOut() {
        return issuedQuantity >= totalQuantity;
    }

    public int getRemainingQuantity() {
        return Math.max(totalQuantity - issuedQuantity, 0);
    }

    /**
     * 메모리에서 +1 (dirty checking 으로 반영).
     * ⚠ 단독으로 쓰면 lost update 가 난다 (3단계 순진한 구현). 아래 둘 중 하나로 보호될 때만 쓴다:
     *  - 비관적 락: SELECT ... FOR UPDATE 로 읽은 엔티티 (커밋까지 다른 트랜잭션이 못 읽고 기다림)
     *  - 낙관적 락: @Version 이 UPDATE 시점에 충돌을 감지 → 재시도
     * (docs/concurrency-notes.md)
     */
    public void increaseIssuedQuantity() {
        if (isSoldOut()) {
            throw new IllegalStateException("재고가 없는 쿠폰은 차감할 수 없습니다.");
        }
        this.issuedQuantity++;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getIssuedQuantity() { return issuedQuantity; }
    public Instant getIssueStartAt() { return issueStartAt; }
    public Instant getIssueEndAt() { return issueEndAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

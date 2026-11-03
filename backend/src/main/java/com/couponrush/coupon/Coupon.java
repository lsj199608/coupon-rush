package com.couponrush.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int totalQuantity;

    @Column(nullable = false)
    private int issuedQuantity;

    @Column(nullable = false)
    private Instant issueStartAt;

    @Column(nullable = false)
    private Instant issueEndAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Coupon() {
    }

    private Coupon(String name, int totalQuantity, Instant issueStartAt, Instant issueEndAt) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
    }

    public static Coupon create(String name, int totalQuantity, Instant issueStartAt, Instant issueEndAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("쿠폰 이름은 필수입니다.");
        }
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("총 수량은 1 이상이어야 합니다.");
        }
        if (issueStartAt == null || issueEndAt == null || !issueStartAt.isBefore(issueEndAt)) {
            throw new IllegalArgumentException("발급 시작 시각은 종료 시각보다 앞서야 합니다.");
        }
        return new Coupon(name.strip(), totalQuantity, issueStartAt, issueEndAt);
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

    // 재고 차감은 엔티티에서 하지 않는다 (메모리 +1 → dirty checking 은 동시 요청에서 lost update 발생).
    // CouponRepository.increaseIssuedQuantity 의 조건부 UPDATE 로만 차감한다. (docs/concurrency-report.md)

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getIssuedQuantity() { return issuedQuantity; }
    public Instant getIssueStartAt() { return issueStartAt; }
    public Instant getIssueEndAt() { return issueEndAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

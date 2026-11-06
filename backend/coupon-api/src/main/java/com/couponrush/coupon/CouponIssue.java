package com.couponrush.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 쿠폰 발급 이력.
 * Coupon/User 를 @ManyToOne 이 아니라 ID 로만 참조한다:
 * 발급 경로에서 연관 엔티티를 로딩할 일이 없고, 4~5단계에서 Redis/Kafka 로 쓰기 경로를 바꿀 때도 결합이 적다.
 * (무결성은 DB 의 FK 와 (coupon_id, user_id) 유니크 제약이 보장)
 */
@Entity
@Table(name = "coupon_issues")
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long couponId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant issuedAt;

    protected CouponIssue() {
    }

    public CouponIssue(Long couponId, Long userId, Instant issuedAt) {
        this.couponId = couponId;
        this.userId = userId;
        this.issuedAt = issuedAt;
    }

    public Long getId() { return id; }
    public Long getCouponId() { return couponId; }
    public Long getUserId() { return userId; }
    public Instant getIssuedAt() { return issuedAt; }
}

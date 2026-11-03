package com.couponrush.coupon;

import com.couponrush.coupon.dto.MyCouponResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    long countByCouponId(Long couponId);

    /** 연관관계 없이 ID 로 조인 (Hibernate 6 의 엔티티 조인) — N+1 없이 쿠폰 이름까지 한 번에 */
    @Query("""
            SELECT new com.couponrush.coupon.dto.MyCouponResponse(ci.id, c.id, c.name, ci.issuedAt)
            FROM CouponIssue ci JOIN Coupon c ON c.id = ci.couponId
            WHERE ci.userId = :userId
            ORDER BY ci.issuedAt DESC, ci.id DESC
            """)
    List<MyCouponResponse> findMyCoupons(@Param("userId") Long userId);
}

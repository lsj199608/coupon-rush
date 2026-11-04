package com.couponrush.coupon.request;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueRequestRepository extends JpaRepository<IssueRequest, String> {

    long countByCouponIdAndStatus(Long couponId, IssueRequestStatus status);
}

package com.couponrush.coupon;

import com.couponrush.coupon.dto.CouponCreateRequest;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 쿠폰 생성/조회 (발급은 CouponIssueService) */
@Service
public class CouponService {

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    @Transactional
    public Coupon create(CouponCreateRequest request) {
        return couponRepository.save(Coupon.create(
                request.name(), request.totalQuantity(), request.issueStartAt(), request.issueEndAt()));
    }

    @Transactional(readOnly = true)
    public Coupon get(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}

package com.couponrush.coupon;

import com.couponrush.coupon.dto.CouponCreateRequest;
import com.couponrush.coupon.dto.CouponResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** /api/admin/** 는 SecurityConfig 에서 ROLE_ADMIN 만 허용 */
@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
        Coupon coupon = couponService.create(request);
        return ResponseEntity.created(URI.create("/api/coupons/" + coupon.getId()))
                .body(CouponResponse.from(coupon));
    }
}

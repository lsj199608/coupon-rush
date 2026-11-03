package com.couponrush.coupon;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.coupon.dto.CouponIssueResponse;
import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.coupon.dto.MyCouponResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;

    public CouponController(CouponService couponService, CouponIssueService couponIssueService) {
        this.couponService = couponService;
        this.couponIssueService = couponIssueService;
    }

    /** 쿠폰 정보 + 남은 수량. 이벤트 페이지에서 로그인 전에도 볼 수 있게 공개 */
    @GetMapping("/api/coupons/{couponId}")
    public CouponResponse get(@PathVariable Long couponId) {
        return CouponResponse.from(couponService.get(couponId));
    }

    /** 선착순 발급. 발급자는 토큰에서 꺼낸다 (요청 바디로 userId 를 받지 않는다 → 남의 이름으로 발급 불가) */
    @PostMapping("/api/coupons/{couponId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponIssueResponse issue(@PathVariable Long couponId, @AuthenticationPrincipal AuthUser authUser) {
        return CouponIssueResponse.from(couponIssueService.issue(couponId, authUser.userId()));
    }

    @GetMapping("/api/users/me/coupons")
    public List<MyCouponResponse> myCoupons(@AuthenticationPrincipal AuthUser authUser) {
        return couponIssueService.getMyCoupons(authUser.userId());
    }
}

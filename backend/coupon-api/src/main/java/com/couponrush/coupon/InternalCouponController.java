package com.couponrush.coupon;

import com.couponrush.coupon.dto.MyCouponResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 서비스 간 내부 API (ROLE_INTERNAL_SERVICE 만 — SecurityConfig, InternalServiceAuthenticationFilter).
 * 지금은 mcp-server 가 챗봇(llm-service) 대신 "이 사용자의 쿠폰"을 물을 때 쓴다.
 * 사용자 ID 는 coupon-api 가 JWT 로 확인한 값이 Kafka 메시지(requestedBy)로 llm-service 에 가고, llm-service 가 mcp-server 에 헤더로 넘긴 것
 * — 모델이 고른 값이 아니다. 응답 형식은 GET /api/users/me/coupons 와 같다.
 * <p>
 * 리버스 프록시 뒤에 배포할 때는 /internal/** 를 외부로 내보내지 않는다 (토큰이 있어도 경로부터 막는 이중 방어).
 */
@RestController
public class InternalCouponController {

    private final CouponIssueService couponIssueService;

    public InternalCouponController(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    @GetMapping("/internal/users/{userId}/coupons")
    public List<MyCouponResponse> userCoupons(@PathVariable Long userId) {
        return couponIssueService.getMyCoupons(userId);
    }
}

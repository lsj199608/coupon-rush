package com.couponrush.coupon;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.coupon.dto.CouponIssueResponse;
import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.coupon.dto.IssueAcceptedResponse;
import com.couponrush.coupon.dto.MyCouponResponse;
import com.couponrush.coupon.issue.IssueResult;
import com.couponrush.coupon.request.IssueRequestStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class CouponController {

    private final CouponService couponService;
    private final CouponIssueService couponIssueService;

    public CouponController(CouponService couponService, CouponIssueService couponIssueService) {
        this.couponService = couponService;
        this.couponIssueService = couponIssueService;
    }

    /** 이벤트(쿠폰) 목록 — 상태(UPCOMING/OPEN/ENDED)와 남은 수량 포함. 로그인 전에도 볼 수 있게 공개 */
    @GetMapping("/api/coupons")
    public List<CouponResponse> list() {
        return couponService.list();
    }

    /** 쿠폰 정보 + 남은 수량 + 상태 + 서버 시각. 이벤트 페이지에서 로그인 전에도 볼 수 있게 공개 */
    @GetMapping("/api/coupons/{couponId}")
    public CouponResponse get(@PathVariable Long couponId) {
        return couponService.getDetail(couponId);
    }

    /**
     * 선착순 발급. 발급자는 토큰에서 꺼낸다 (요청 바디로 userId 를 받지 않는다 → 남의 이름으로 발급 불가)
     * - 비동기(기본): 202 Accepted + requestId, Location: /api/issue-requests/{requestId}
     *   "선착순 판정은 끝났고 DB 저장은 처리 중"이라는 뜻. 아직 쿠폰(리소스)이 만들어지지 않았으므로 201 이 아니다.
     * - 동기: 201 Created + 발급 결과
     */
    @PostMapping("/api/coupons/{couponId}/issue")
    public ResponseEntity<?> issue(@PathVariable Long couponId, @AuthenticationPrincipal AuthUser authUser) {
        return switch (couponIssueService.request(couponId, authUser.userId())) {
            case IssueResult.Issued issued -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(CouponIssueResponse.from(issued.issue()));
            case IssueResult.Accepted accepted -> ResponseEntity.accepted()
                    .location(URI.create("/api/issue-requests/" + accepted.requestId()))
                    .body(new IssueAcceptedResponse(accepted.requestId(), accepted.couponId(), IssueRequestStatus.PENDING));
        };
    }

    @GetMapping("/api/users/me/coupons")
    public List<MyCouponResponse> myCoupons(@AuthenticationPrincipal AuthUser authUser) {
        return couponIssueService.getMyCoupons(authUser.userId());
    }
}

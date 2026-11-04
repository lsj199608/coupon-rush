package com.couponrush.coupon;

import com.couponrush.coupon.dto.CouponCreateRequest;
import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.coupon.dto.CouponStockResponse;
import com.couponrush.coupon.reconcile.CouponStockReconciler;
import com.couponrush.coupon.reconcile.StockReconciliationResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final CouponStockReconciler reconciler;

    public AdminCouponController(CouponService couponService, CouponStockReconciler reconciler) {
        this.couponService = couponService;
        this.reconciler = reconciler;
    }

    /** 쿠폰 생성. DB 커밋 후 Redis 에 재고를 적재한다 (이벤트 오픈 전 미리 적재) */
    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
        Coupon coupon = couponService.create(request);
        return ResponseEntity.created(URI.create("/api/coupons/" + coupon.getId()))
                .body(CouponResponse.from(coupon));
    }

    /**
     * DB 발급 이력 기준으로 Redis 재고를 다시 적재 (Redis 데이터 유실, 적재 실패, 불일치 복구용).
     * 발급이 진행 중이 아닐 때 호출한다.
     */
    @PostMapping("/{couponId}/stock/sync")
    public CouponStockResponse syncStock(@PathVariable Long couponId) {
        return couponService.syncStock(couponId);
    }

    /**
     * Redis 재고 ↔ DB 발급 대조 (읽기 전용, 고치지 않는다). 불일치면 서버 로그에도 WARN 으로 남는다.
     * 진행 중인 이벤트는 처리 중인 요청 때문에 일시적으로 어긋날 수 있다 (eventEnded 확인).
     */
    @GetMapping("/{couponId}/reconciliation")
    public StockReconciliationResult reconcile(@PathVariable Long couponId) {
        return reconciler.reconcile(couponId);
    }
}

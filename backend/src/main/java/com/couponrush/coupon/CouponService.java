package com.couponrush.coupon;

import com.couponrush.coupon.dto.CouponCreateRequest;
import com.couponrush.coupon.dto.CouponStockResponse;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 쿠폰 생성/조회/재고 적재 (발급은 CouponIssueService) */
@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final RedisCouponStock redisCouponStock;

    public CouponService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
                         RedisCouponStock redisCouponStock) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.redisCouponStock = redisCouponStock;
    }

    /**
     * 쿠폰 생성 + Redis 재고 적재 (발급 시작 전에 미리 올려 둔다).
     * 적재는 DB 커밋 "이후"에 한다: DB 가 원본이므로, 롤백된 쿠폰의 재고가 Redis 에 남는 일이 없게 하기 위함.
     * 적재가 실패하면 쿠폰은 만들어진 상태로 예외가 나간다 → POST /api/admin/coupons/{id}/stock/sync 로 다시 적재.
     * (발급 방식이 DB 계열이어도 적재해 둔다 → 설정만 바꿔 Redis 방식으로 전환할 수 있게)
     */
    @Transactional
    public Coupon create(CouponCreateRequest request) {
        Coupon coupon = couponRepository.save(Coupon.create(
                request.name(), request.totalQuantity(), request.issueStartAt(), request.issueEndAt()));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisCouponStock.load(coupon, List.of());
            }
        });
        return coupon;
    }

    @Transactional(readOnly = true)
    public Coupon get(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }

    /**
     * DB(발급 이력) 기준으로 Redis 재고를 다시 만든다. Redis 데이터 유실·불일치 복구용.
     * 남은 재고 = 총 수량 - 발급 이력 수, 발급 Set = 발급 이력의 userId.
     * ⚠ 발급이 진행 중이 아닐 때만 호출한다 (RedisCouponStock.load 주석 참고).
     */
    @Transactional(readOnly = true)
    public CouponStockResponse syncStock(Long couponId) {
        Coupon coupon = get(couponId);
        List<Long> issuedUserIds = couponIssueRepository.findUserIdsByCouponId(couponId);
        redisCouponStock.load(coupon, issuedUserIds);
        return new CouponStockResponse(couponId,
                redisCouponStock.getStock(couponId).orElse(0), redisCouponStock.getIssuedCount(couponId));
    }
}

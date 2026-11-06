package com.couponrush.coupon;

import com.couponrush.coupon.dto.CouponCreateRequest;
import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.coupon.dto.CouponStockResponse;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 쿠폰 생성/조회/재고 적재 (발급은 CouponIssueService) */
@Service
public class CouponService {

    /** 목록은 최근 이벤트만 (페이지네이션은 이벤트가 많아지면) */
    private static final int LIST_LIMIT = 50;

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final RedisCouponStock redisCouponStock;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
                         RedisCouponStock redisCouponStock, Clock clock) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.redisCouponStock = redisCouponStock;
        this.clock = clock;
    }

    /** 이벤트 상세 (공개). 남은 수량은 Redis 재고 기준 */
    @Transactional(readOnly = true)
    public CouponResponse getDetail(Long couponId) {
        Coupon coupon = get(couponId);
        return CouponResponse.of(coupon, redisCouponStock.getStock(couponId).orElse(null), clock.instant());
    }

    /**
     * 이벤트 목록 (공개). 오픈 시각 최신순 50개.
     * 남은 수량은 Redis 재고를 MGET 한 번으로 모아 읽는다 (이벤트마다 왕복하지 않게).
     */
    @Transactional(readOnly = true)
    public List<CouponResponse> list() {
        List<Coupon> coupons = couponRepository.findAll(
                PageRequest.of(0, LIST_LIMIT, Sort.by(Sort.Direction.DESC, "issueStartAt", "id"))).getContent();
        Map<Long, Integer> stocks = redisCouponStock.getStocks(coupons.stream().map(Coupon::getId).toList());
        Instant now = clock.instant();
        return coupons.stream()
                .map(coupon -> CouponResponse.of(coupon, stocks.get(coupon.getId()), now))
                .toList();
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
                request.name(), request.description(), request.totalQuantity(), request.issueStartAt(), request.issueEndAt()));

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

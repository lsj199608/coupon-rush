package com.couponrush.coupon.reconcile;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponIssueRepository;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis 재고와 DB 발급 이력의 대조 점검. 고치지 않고 "불일치만 로그"로 남긴다 (자동 수정은 판단이 필요해서 사람이 한다).
 *
 * 불변식 (정상이면 언제나 참):
 *  1) Redis 남은 재고 + DB 발급 행 = 총 수량
 *  2) Redis issued Set 크기 = DB 발급 행, 그리고 두 집합의 사용자가 같다
 *  3) coupons.issued_quantity = DB 발급 행
 * 이벤트 진행 중에는 처리 중인 요청(Redis 는 차감, DB 는 아직) 때문에 1·2 가 잠깐 어긋날 수 있다 → 정기 점검은 종료된 이벤트만 본다.
 */
@Service
public class CouponStockReconciler {

    private static final Logger log = LoggerFactory.getLogger(CouponStockReconciler.class);
    private static final int SAMPLE_SIZE = 20;
    /** 종료 직후에는 컨슈머가 남은 메시지를 처리 중일 수 있어 잠깐 기다린다 */
    static final Duration GRACE_AFTER_END = Duration.ofMinutes(1);
    /** Redis 키는 종료 7일 후 만료 → 그 안의 이벤트만 대조할 수 있다 */
    static final Duration LOOKBACK = Duration.ofDays(7);

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final RedisCouponStock redisStock;
    private final Clock clock;

    public CouponStockReconciler(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
                                 RedisCouponStock redisStock, Clock clock) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.redisStock = redisStock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockReconciliationResult reconcile(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        StockReconciliationResult result = compare(coupon);
        logIfInconsistent(result);
        return result;
    }

    /** 최근 종료된(1분 ~ 7일 전) 이벤트를 모두 대조한다. 스케줄러가 호출 */
    @Transactional(readOnly = true)
    public List<StockReconciliationResult> reconcileRecentlyEnded() {
        Instant now = clock.instant();
        List<Coupon> ended = couponRepository.findByIssueEndAtBetween(now.minus(LOOKBACK), now.minus(GRACE_AFTER_END));
        List<StockReconciliationResult> inconsistent = new ArrayList<>();
        for (Coupon coupon : ended) {
            StockReconciliationResult result = compare(coupon);
            logIfInconsistent(result);
            if (!result.consistent()) {
                inconsistent.add(result);
            }
        }
        log.info("재고 대조 점검 완료: 종료된 이벤트 {}개, 불일치 {}개", ended.size(), inconsistent.size());
        return inconsistent;
    }

    private StockReconciliationResult compare(Coupon coupon) {
        Long couponId = coupon.getId();
        boolean ended = !clock.instant().isBefore(coupon.getIssueEndAt());
        List<Long> dbUserIdList = couponIssueRepository.findUserIdsByCouponId(couponId);
        Set<Long> dbUserIds = new HashSet<>(dbUserIdList);
        long dbIssued = dbUserIdList.size();
        List<String> problems = new ArrayList<>();

        if (coupon.getIssuedQuantity() != dbIssued) {
            problems.add("DB 카운터(issued_quantity=" + coupon.getIssuedQuantity() + ") ≠ DB 발급 행(" + dbIssued + ")");
        }

        if (!redisStock.isLoaded(couponId)) {
            return new StockReconciliationResult(couponId, coupon.getTotalQuantity(), ended, false, null,
                    0, dbIssued, coupon.getIssuedQuantity(), 0, List.of(), 0, List.of(), problems);
        }

        int redisRemaining = redisStock.getStock(couponId).orElse(0);
        Set<Long> redisUserIds = redisStock.getIssuedUserIds(couponId);

        Set<Long> onlyInRedis = new HashSet<>(redisUserIds);
        onlyInRedis.removeAll(dbUserIds);
        Set<Long> onlyInDb = new HashSet<>(dbUserIds);
        onlyInDb.removeAll(redisUserIds);

        if (redisRemaining + dbIssued != coupon.getTotalQuantity()) {
            problems.add("Redis 남은 재고(" + redisRemaining + ") + DB 발급(" + dbIssued + ") ≠ 총 수량(" + coupon.getTotalQuantity() + ")");
        }
        if (redisUserIds.size() != dbIssued) {
            problems.add("Redis 발급 Set(" + redisUserIds.size() + ") ≠ DB 발급(" + dbIssued + ")");
        }
        if (!onlyInRedis.isEmpty()) {
            problems.add("Redis 에만 있는 사용자 " + onlyInRedis.size() + "명 (유령 발급 의심: 차감됐지만 DB 저장 안 됨)");
        }
        if (!onlyInDb.isEmpty()) {
            problems.add("DB 에만 있는 사용자 " + onlyInDb.size() + "명 (Redis Set 유실 또는 발행 실패 후 처리 의심)");
        }

        return new StockReconciliationResult(couponId, coupon.getTotalQuantity(), ended, true, redisRemaining,
                redisUserIds.size(), dbIssued, coupon.getIssuedQuantity(),
                onlyInRedis.size(), sample(onlyInRedis), onlyInDb.size(), sample(onlyInDb), problems);
    }

    private static void logIfInconsistent(StockReconciliationResult r) {
        if (r.consistent()) {
            log.debug("재고 대조 일치: couponId={}", r.couponId());
            return;
        }
        log.warn("재고 대조 불일치: couponId={}, 종료={}, Redis 적재={}, 문제={}, Redis에만={}, DB에만={}{}",
                r.couponId(), r.eventEnded(), r.redisLoaded(), r.problems(), r.onlyInRedisSample(), r.onlyInDbSample(),
                r.eventEnded() ? "" : " — 진행 중인 이벤트라 처리 중인 요청 때문일 수 있음");
    }

    private static List<Long> sample(Set<Long> ids) {
        return ids.stream().sorted().limit(SAMPLE_SIZE).toList();
    }
}

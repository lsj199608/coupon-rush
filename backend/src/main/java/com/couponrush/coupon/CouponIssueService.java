package com.couponrush.coupon;

import com.couponrush.coupon.dto.MyCouponResponse;
import com.couponrush.coupon.issue.CouponIssueProperties;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 쿠폰 발급 진입점. 동시성 제어 방식(CouponIssuer)은 설정으로 고른다 — 기본은 조건부 UPDATE.
 * 쿠폰 발급 핵심 경로에는 LLM 호출 등 외부 호출을 절대 넣지 않는다 (CLAUDE.md 규칙).
 * 방식별 비교: docs/concurrency-notes.md
 */
@Service
public class CouponIssueService {

    private final Map<IssueStrategy, CouponIssuer> issuers = new EnumMap<>(IssueStrategy.class);
    private final IssueStrategy strategy;
    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueService(List<CouponIssuer> issuerBeans, CouponIssueProperties properties,
                              CouponIssueRepository couponIssueRepository) {
        issuerBeans.forEach(issuer -> issuers.put(issuer.strategy(), issuer));
        Arrays.stream(IssueStrategy.values())
                .filter(s -> !issuers.containsKey(s))
                .findAny()
                .ifPresent(s -> {
                    throw new IllegalStateException("구현체가 없는 발급 방식: " + s);
                });
        this.strategy = properties.strategy();
        this.couponIssueRepository = couponIssueRepository;
    }

    /**
     * 트랜잭션을 여기서 열지 않는다 — 각 방식이 직접 관리한다.
     * (낙관적 락은 재시도마다 새 트랜잭션이 필요해서 바깥에서 감싸면 재시도가 깨진다)
     */
    public CouponIssue issue(Long couponId, Long userId) {
        return issuers.get(strategy).issue(couponId, userId);
    }

    public IssueStrategy getStrategy() {
        return strategy;
    }

    @Transactional(readOnly = true)
    public List<MyCouponResponse> getMyCoupons(Long userId) {
        return couponIssueRepository.findMyCoupons(userId);
    }
}

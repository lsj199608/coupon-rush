package com.couponrush.coupon.search;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 검색 조건 → SQL WHERE. 조건이 정해진 모양(단어·상태·기간·수량)뿐이라 문자열 SQL 대신 Criteria 로 조립한다
 * — 단어는 항상 바인딩 파라미터가 되고(SQL 인젝션 없음), 빈 조건은 WHERE 에서 빠진다.
 */
public final class CouponSpecifications {

    private static final char ESCAPE = '\\';

    private CouponSpecifications() {
    }

    /**
     * @param now 상태(UPCOMING/OPEN/ENDED)를 판정할 시각 — 응답의 status 와 같은 기준(CouponStatus.of)
     */
    public static Specification<Coupon> matching(CouponSearchCondition condition, Instant now) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            keywords(condition, root, cb).ifPresent(where::add);
            if (!condition.statuses().isEmpty()) {
                where.add(cb.or(condition.statuses().stream()
                        .map(status -> status(status, root, cb, now)).toArray(Predicate[]::new)));
            }
            range(root.get("issueStartAt"), condition.startFrom(), condition.startTo(), cb, where);
            range(root.get("issueEndAt"), condition.endFrom(), condition.endTo(), cb, where);
            if (condition.availableOnly()) {
                // DB 카운터는 비동기 발급에서 늦게 따라오므로(실제보다 적게 발급된 값) "DB 로 소진"이면 확실히 소진이다.
                // 남은 것처럼 보이는 행은 서비스가 Redis 재고로 한 번 더 거른다
                where.add(cb.lessThan(root.get("issuedQuantity"), root.get("totalQuantity")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Optional<Predicate> keywords(CouponSearchCondition condition, Root<Coupon> root, CriteriaBuilder cb) {
        if (condition.keywords().isEmpty()) {
            return Optional.empty();
        }
        Predicate[] each = condition.keywords().stream().map(keyword -> {
            String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
            // description 이 NULL 이면 LIKE 결과도 NULL → OR 에서 이름 조건만 남는다
            return cb.or(cb.like(cb.lower(root.get("name")), pattern, ESCAPE),
                    cb.like(cb.lower(root.get("description")), pattern, ESCAPE));
        }).toArray(Predicate[]::new);
        return Optional.of(condition.keywordMatch() == CouponSearchCondition.KeywordMatch.ALL
                ? cb.and(each) : cb.or(each));
    }

    /** CouponStatus.of 와 같은 규칙: 발급 기간 [start, end) */
    private static Predicate status(CouponStatus status, Root<Coupon> root, CriteriaBuilder cb, Instant now) {
        Expression<Instant> start = root.get("issueStartAt");
        Expression<Instant> end = root.get("issueEndAt");
        return switch (status) {
            case UPCOMING -> cb.greaterThan(start, now);
            case OPEN -> cb.and(cb.lessThanOrEqualTo(start, now), cb.greaterThan(end, now));
            case ENDED -> cb.lessThanOrEqualTo(end, now);
        };
    }

    private static void range(Expression<Instant> field, Instant from, Instant to, CriteriaBuilder cb, List<Predicate> where) {
        if (from != null) {
            where.add(cb.greaterThanOrEqualTo(field, from));
        }
        if (to != null) {
            where.add(cb.lessThan(field, to));
        }
    }

    /** 단어 속 %, _ 를 글자 그대로 찾게 한다 ("50%" 가 "아무 글자"로 해석되지 않게) */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

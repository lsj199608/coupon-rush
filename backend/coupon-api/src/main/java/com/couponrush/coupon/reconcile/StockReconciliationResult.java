package com.couponrush.coupon.reconcile;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Redis 재고 ↔ DB 발급 대조 결과.
 *
 * @param eventEnded       false 면 진행 중 — 처리 중인 요청(PENDING) 때문에 일시적으로 어긋날 수 있다
 * @param redisLoaded      false 면 Redis 키가 없다 (적재 안 됨 / 종료 7일 후 만료) — 수량 비교는 하지 않는다
 * @param onlyInRedisCount Set 에는 있는데 DB 발급 이력이 없는 사용자 수 (유령 발급 의심)
 * @param onlyInDbCount    DB 에는 있는데 Set 에 없는 사용자 수 (Set 유실 / 발행 타임아웃 후 처리 등)
 * @param onlyInRedisSample / onlyInDbSample 최대 20명 (로그/화면용)
 */
public record StockReconciliationResult(
        Long couponId,
        int totalQuantity,
        boolean eventEnded,
        boolean redisLoaded,
        Integer redisRemaining,
        long redisIssuedCount,
        long dbIssuedCount,
        int dbIssuedQuantity,
        long onlyInRedisCount,
        List<Long> onlyInRedisSample,
        long onlyInDbCount,
        List<Long> onlyInDbSample,
        List<String> problems) {

    /** record 컴포넌트가 아니라서 Jackson 이 기본으로 내보내지 않는다 → 응답에 포함되게 명시 */
    @JsonProperty("consistent")
    public boolean consistent() {
        return problems.isEmpty();
    }
}

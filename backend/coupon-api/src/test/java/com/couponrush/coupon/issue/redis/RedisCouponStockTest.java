package com.couponrush.coupon.issue.redis;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.Coupon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Lua 스크립트 단위 동작 (실제 Redis 컨테이너) */
@IntegrationTest
class RedisCouponStockTest {

    private static final long COUPON_ID = 777L;

    @Autowired RedisCouponStock stock;
    @Autowired StringRedisTemplate redisTemplate;

    // 실제 현재 시각 기준이어야 한다 — 적재 시 키 만료 = 발급 종료 + 7일(절대 시각)이라, 고정 날짜를 쓰면 그 날짜 + 7일이 지난 뒤
    // 키가 적재 즉시 만료돼 NOT_READY 가 된다 (2026-11-04 고정값이 11-13 에 깨졌음)
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @AfterEach
    void tearDown() {
        redisTemplate.delete(redisTemplate.keys("event:*"));
    }

    @Test
    void 적재하면_재고_기간_만료시각이_설정된다() {
        Coupon coupon = coupon(3, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));

        stock.load(coupon, List.of());

        assertThat(redisTemplate.opsForValue().get("event:{777}:stock")).isEqualTo("3");
        assertThat(redisTemplate.opsForHash().get("event:{777}:meta", "start"))
                .isEqualTo(String.valueOf(coupon.getIssueStartAt().toEpochMilli()));
        assertThat(redisTemplate.getExpire("event:{777}:stock", TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void 재고만큼_SUCCESS_그다음은_SOLD_OUT() {
        stock.load(coupon(2, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());

        assertThat(stock.tryIssue(COUPON_ID, 1L, now)).isEqualTo(RedisIssueResult.SUCCESS);
        assertThat(stock.tryIssue(COUPON_ID, 2L, now)).isEqualTo(RedisIssueResult.SUCCESS);
        assertThat(stock.tryIssue(COUPON_ID, 3L, now)).isEqualTo(RedisIssueResult.SOLD_OUT);

        assertThat(stock.getStock(COUPON_ID)).contains(0);
        assertThat(stock.getIssuedCount(COUPON_ID)).isEqualTo(2);
    }

    @Test
    void 같은_사용자는_DUPLICATE_이고_재고는_줄지_않는다() {
        stock.load(coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());
        stock.tryIssue(COUPON_ID, 1L, now);

        assertThat(stock.tryIssue(COUPON_ID, 1L, now)).isEqualTo(RedisIssueResult.DUPLICATE);
        assertThat(stock.getStock(COUPON_ID)).contains(4);
    }

    @Test
    void 첫_발급으로_만들어진_Set_에도_만료시각이_붙는다() {
        stock.load(coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());

        stock.tryIssue(COUPON_ID, 1L, now);

        assertThat(redisTemplate.getExpire("event:{777}:issued", TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void 기간_밖이면_NOT_IN_PERIOD_시작_포함_종료_미포함() {
        Instant start = now;
        Instant end = now.plus(Duration.ofHours(1));
        stock.load(coupon(5, start, end), List.of());

        assertThat(stock.tryIssue(COUPON_ID, 1L, start.minusMillis(1))).isEqualTo(RedisIssueResult.NOT_IN_PERIOD);
        assertThat(stock.tryIssue(COUPON_ID, 2L, start)).isEqualTo(RedisIssueResult.SUCCESS);
        assertThat(stock.tryIssue(COUPON_ID, 3L, end)).isEqualTo(RedisIssueResult.NOT_IN_PERIOD);
    }

    @Test
    void 적재되지_않은_쿠폰은_NOT_READY() {
        assertThat(stock.tryIssue(999L, 1L, now)).isEqualTo(RedisIssueResult.NOT_READY);
    }

    @Test
    void 보상하면_재고가_돌아오고_Set_에서_빠진다() {
        stock.load(coupon(1, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());
        stock.tryIssue(COUPON_ID, 1L, now);

        stock.restore(COUPON_ID, 1L, true, "req-1");

        assertThat(stock.getStock(COUPON_ID)).contains(1);
        assertThat(stock.getIssuedCount(COUPON_ID)).isZero();
        assertThat(stock.tryIssue(COUPON_ID, 1L, now)).isEqualTo(RedisIssueResult.SUCCESS);   // 다시 받을 수 있다
    }

    @Test
    void 같은_restoreId_로_여러_번_보상해도_재고는_한_번만_늘어난다() {
        stock.load(coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());
        stock.tryIssue(COUPON_ID, 1L, now);   // 재고 4

        boolean first = stock.restore(COUPON_ID, 1L, true, "req-1");
        boolean second = stock.restore(COUPON_ID, 1L, true, "req-1");   // 발행 실패 보상 + DLT 보상이 겹친 상황
        boolean third = stock.restore(COUPON_ID, 1L, false, "req-1");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(third).isFalse();
        assertThat(stock.getStock(COUPON_ID)).as("한 번만 복구").contains(5);
        assertThat(stock.isRestored(COUPON_ID, "req-1")).isTrue();
        assertThat(stock.isRestored(COUPON_ID, "req-2")).isFalse();
    }

    @Test
    void 다른_요청의_보상은_각각_반영된다() {
        stock.load(coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());
        stock.tryIssue(COUPON_ID, 1L, now);
        stock.tryIssue(COUPON_ID, 2L, now);   // 재고 3

        stock.restore(COUPON_ID, 1L, true, "req-1");
        stock.restore(COUPON_ID, 2L, true, "req-2");

        assertThat(stock.getStock(COUPON_ID)).contains(5);
        assertThat(stock.getIssuedCount(COUPON_ID)).isZero();
    }

    @Test
    void 복구_기록에도_재고_키와_같은_만료가_붙는다() {
        stock.load(coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))), List.of());
        stock.tryIssue(COUPON_ID, 1L, now);

        stock.restore(COUPON_ID, 1L, true, "req-1");

        assertThat(redisTemplate.getExpire(RedisCouponStock.restoredKey(COUPON_ID), TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void 재적재해도_복구_기록은_남는다_Kafka_에_남은_취소_요청_대비() {
        Coupon coupon = coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        stock.load(coupon, List.of());
        stock.tryIssue(COUPON_ID, 1L, now);
        stock.restore(COUPON_ID, 1L, true, "req-1");

        stock.load(coupon, List.of());

        assertThat(stock.isRestored(COUPON_ID, "req-1")).isTrue();
    }

    @Test
    void 키가_없으면_보상해도_재고_키를_새로_만들지_않는다() {
        stock.restore(COUPON_ID, 1L, true, "req-1");

        assertThat(redisTemplate.hasKey("event:{777}:stock")).isFalse();
    }

    @Test
    void 재동기화하면_기존_발급자를_반영한_재고로_다시_만든다() {
        Coupon coupon = coupon(5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));

        stock.load(coupon, List.of(10L, 11L));

        assertThat(stock.getStock(COUPON_ID)).contains(3);
        assertThat(stock.tryIssue(COUPON_ID, 10L, now)).isEqualTo(RedisIssueResult.DUPLICATE);
        assertThat(stock.tryIssue(COUPON_ID, 12L, now)).isEqualTo(RedisIssueResult.SUCCESS);
    }

    private static Coupon coupon(int total, Instant start, Instant end) {
        Coupon coupon = Coupon.create("Redis 테스트", total, start, end);
        ReflectionTestUtils.setField(coupon, "id", COUPON_ID);   // DB 없이 스크립트만 검증
        return coupon;
    }
}

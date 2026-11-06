package com.couponrush.coupon;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final Instant START = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant END = Instant.parse("2026-11-03T12:00:00Z");

    @Test
    void 생성하면_발급수량은_0_이고_남은수량은_총수량이다() {
        Coupon coupon = Coupon.create("  선착순 쿠폰  ", 100, START, END);

        assertThat(coupon.getName()).isEqualTo("선착순 쿠폰");
        assertThat(coupon.getIssuedQuantity()).isZero();
        assertThat(coupon.getRemainingQuantity()).isEqualTo(100);
        assertThat(coupon.isSoldOut()).isFalse();
    }

    @Test
    void 발급기간은_시작_포함_종료_미포함이다() {
        Coupon coupon = Coupon.create("쿠폰", 1, START, END);

        assertThat(coupon.isInIssuePeriod(START.minusNanos(1))).isFalse();
        assertThat(coupon.isInIssuePeriod(START)).isTrue();
        assertThat(coupon.isInIssuePeriod(END.minus(Duration.ofSeconds(1)))).isTrue();
        assertThat(coupon.isInIssuePeriod(END)).isFalse();
    }

    @Test
    void 잘못된_값으로는_생성할_수_없다() {
        assertThatThrownBy(() -> Coupon.create(" ", 1, START, END)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.create("쿠폰", 0, START, END)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.create("쿠폰", 1, END, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.create("쿠폰", 1, START, START)).isInstanceOf(IllegalArgumentException.class);
    }
}

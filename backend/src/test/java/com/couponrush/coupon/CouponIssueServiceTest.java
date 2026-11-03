package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 단일 요청 시나리오 (동시성은 CouponIssueConcurrencyTest) */
@IntegrationTest
class CouponIssueServiceTest {

    @Autowired CouponIssueService couponIssueService;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    User user;

    @BeforeEach
    void setUp() {
        user = newUser();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM coupon_issues");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void 발급하면_이력이_저장되고_발급수량이_1_늘어난다() {
        Coupon coupon = openCoupon(10);

        CouponIssue issue = couponIssueService.issue(coupon.getId(), user.getId());

        assertThat(issue.getId()).isNotNull();
        assertThat(issue.getCouponId()).isEqualTo(coupon.getId());
        assertThat(issue.getUserId()).isEqualTo(user.getId());
        assertThat(reload(coupon).getIssuedQuantity()).isEqualTo(1);
        assertThat(reload(coupon).getUpdatedAt()).isAfterOrEqualTo(coupon.getUpdatedAt());
    }

    @Test
    void 이미_받은_쿠폰을_다시_요청하면_COUPON_ALREADY_ISSUED_이고_재고는_그대로다() {
        Coupon coupon = openCoupon(10);
        couponIssueService.issue(coupon.getId(), user.getId());

        assertErrorCode(() -> couponIssueService.issue(coupon.getId(), user.getId()), ErrorCode.COUPON_ALREADY_ISSUED);
        assertThat(reload(coupon).getIssuedQuantity()).isEqualTo(1);
    }

    @Test
    void 재고가_없으면_COUPON_SOLD_OUT() {
        Coupon coupon = openCoupon(1);
        couponIssueService.issue(coupon.getId(), user.getId());

        assertErrorCode(() -> couponIssueService.issue(coupon.getId(), newUser().getId()), ErrorCode.COUPON_SOLD_OUT);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    @Test
    void 발급_시작_전이나_종료_후면_COUPON_NOT_IN_PERIOD() {
        Instant now = Instant.now();
        Coupon notStarted = couponRepository.save(Coupon.create("예정", 10, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))));
        Coupon ended = couponRepository.save(Coupon.create("종료", 10, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));

        assertErrorCode(() -> couponIssueService.issue(notStarted.getId(), user.getId()), ErrorCode.COUPON_NOT_IN_PERIOD);
        assertErrorCode(() -> couponIssueService.issue(ended.getId(), user.getId()), ErrorCode.COUPON_NOT_IN_PERIOD);
        assertThat(reload(notStarted).getIssuedQuantity()).isZero();
    }

    @Test
    void 없는_쿠폰이면_COUPON_NOT_FOUND() {
        assertErrorCode(() -> couponIssueService.issue(999_999L, user.getId()), ErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 내_쿠폰_목록은_최근_발급순으로_쿠폰_이름과_함께_조회된다() {
        Coupon first = openCoupon(10);
        Coupon second = openCoupon(10);
        couponIssueService.issue(first.getId(), user.getId());
        couponIssueService.issue(second.getId(), user.getId());
        couponIssueService.issue(first.getId(), newUser().getId());   // 다른 사람 발급은 안 보인다

        var myCoupons = couponIssueService.getMyCoupons(user.getId());

        assertThat(myCoupons).extracting(m -> m.couponId()).containsExactly(second.getId(), first.getId());
        assertThat(myCoupons).extracting(m -> m.couponName()).containsOnly("테스트 쿠폰");
    }

    private Coupon openCoupon(int totalQuantity) {
        Instant now = Instant.now();
        return couponRepository.save(Coupon.create("테스트 쿠폰", totalQuantity,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "coupon-" + UUID.randomUUID(), "사용자", null));
    }

    private Coupon reload(Coupon coupon) {
        return couponRepository.findById(coupon.getId()).orElseThrow();
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}

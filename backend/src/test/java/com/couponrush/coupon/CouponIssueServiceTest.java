package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단일 요청 시나리오. 동시성 제어 방식이 달라도 결과 규칙은 같아야 하므로 모든 방식에 대해 돌린다.
 * (동시성은 CouponIssueConcurrencyTest)
 */
@IntegrationTest
class CouponIssueServiceTest {

    @Autowired List<CouponIssuer> issuers;
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
        ConcurrencyTestSupport.cleanUp(jdbcTemplate);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 발급하면_이력이_저장되고_발급수량과_버전이_늘어난다(IssueStrategy strategy) {
        Coupon coupon = openCoupon(10);

        CouponIssue issue = issuer(strategy).issue(coupon.getId(), user.getId());

        assertThat(issue.getId()).isNotNull();
        assertThat(issue.getCouponId()).isEqualTo(coupon.getId());
        assertThat(issue.getUserId()).isEqualTo(user.getId());
        Coupon reloaded = reload(coupon);
        assertThat(reloaded.getIssuedQuantity()).isEqualTo(1);
        assertThat(reloaded.getVersion()).isEqualTo(coupon.getVersion() + 1);   // 조건부 UPDATE 도 버전을 올린다
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 이미_받은_쿠폰을_다시_요청하면_COUPON_ALREADY_ISSUED_이고_재고는_그대로다(IssueStrategy strategy) {
        Coupon coupon = openCoupon(10);
        issuer(strategy).issue(coupon.getId(), user.getId());

        assertErrorCode(() -> issuer(strategy).issue(coupon.getId(), user.getId()), ErrorCode.COUPON_ALREADY_ISSUED);
        assertThat(reload(coupon).getIssuedQuantity()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 재고가_없으면_COUPON_SOLD_OUT(IssueStrategy strategy) {
        Coupon coupon = openCoupon(1);
        issuer(strategy).issue(coupon.getId(), user.getId());

        assertErrorCode(() -> issuer(strategy).issue(coupon.getId(), newUser().getId()), ErrorCode.COUPON_SOLD_OUT);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 발급_시작_전이나_종료_후면_COUPON_NOT_IN_PERIOD(IssueStrategy strategy) {
        Instant now = Instant.now();
        Coupon notStarted = couponRepository.save(Coupon.create("예정", 10, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))));
        Coupon ended = couponRepository.save(Coupon.create("종료", 10, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));

        assertErrorCode(() -> issuer(strategy).issue(notStarted.getId(), user.getId()), ErrorCode.COUPON_NOT_IN_PERIOD);
        assertErrorCode(() -> issuer(strategy).issue(ended.getId(), user.getId()), ErrorCode.COUPON_NOT_IN_PERIOD);
        assertThat(reload(notStarted).getIssuedQuantity()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueStrategy.class)
    void 없는_쿠폰이면_COUPON_NOT_FOUND(IssueStrategy strategy) {
        assertErrorCode(() -> issuer(strategy).issue(999_999L, user.getId()), ErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 기본_발급_방식은_조건부_UPDATE_다() {
        assertThat(couponIssueService.getStrategy()).isEqualTo(IssueStrategy.CONDITIONAL_UPDATE);
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

    private CouponIssuer issuer(IssueStrategy strategy) {
        return ConcurrencyTestSupport.issuer(issuers, strategy);
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

package com.couponrush.coupon.issue.redis;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponIssueRepository;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.coupon.CouponService;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis 방식에만 있는 흐름: DB 실패 시 보상, 재고 미적재, DB 기준 재동기화 */
@IntegrationTest
class RedisCouponIssuerTest {

    @Autowired RedisCouponIssuer issuer;
    @Autowired RedisCouponStock stock;
    @Autowired CouponService couponService;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM coupon_issues");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("event:*"));
    }

    @Test
    void 성공하면_Redis_재고와_DB_발급이력_카운터가_함께_맞는다() {
        Coupon coupon = openCoupon(3);
        User user = newUser();

        issuer.issue(coupon.getId(), user.getId());

        assertThat(stock.getStock(coupon.getId())).contains(2);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getIssuedQuantity()).isEqualTo(1);
    }

    @Test
    void DB_저장이_실패하면_Redis_재고를_되돌린다() {
        Coupon coupon = openCoupon(1);
        long notExistingUserId = 9_999_999L;   // users FK 위반 → INSERT 실패

        assertThatThrownBy(() -> issuer.issue(coupon.getId(), notExistingUserId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(stock.getStock(coupon.getId())).as("재고 원복").contains(1);
        assertThat(stock.getIssuedCount(coupon.getId())).as("Set 에서 제거").isZero();
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isZero();
        // 되돌린 재고로 다른 사람이 정상 발급받을 수 있다
        issuer.issue(coupon.getId(), newUser().getId());
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    @Test
    void INSERT_후_카운터_UPDATE_단계에서_실패해도_INSERT_까지_롤백되고_Redis_가_복구된다() {
        Coupon coupon = openCoupon(3);
        // DB 카운터만 꽉 찬 상태로 만들어 Redis(재고 3)와 어긋나게 한다 → 조건부 UPDATE 가 0행 → 예외
        jdbcTemplate.update("UPDATE coupons SET issued_quantity = total_quantity WHERE id = ?", coupon.getId());
        User user = newUser();

        assertThatThrownBy(() -> issuer.issue(coupon.getId(), user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일치하지 않습니다");

        assertThat(couponIssueRepository.countByCouponId(coupon.getId()))
                .as("먼저 실행된 INSERT 도 같은 트랜잭션이라 롤백").isZero();
        assertThat(stock.getStock(coupon.getId())).as("재고 원복").contains(3);
        assertThat(redisTemplate.opsForSet().isMember(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId())))
                .as("Set 에서 제거 → 원인 해결 후 다시 신청 가능").isFalse();
    }

    @Test
    void 보상_후_같은_사용자가_다시_요청하면_정상_발급된다() {
        Coupon coupon = openCoupon(1);
        User user = newUser();
        jdbcTemplate.update("UPDATE coupons SET issued_quantity = total_quantity WHERE id = ?", coupon.getId());
        assertThatThrownBy(() -> issuer.issue(coupon.getId(), user.getId())).isInstanceOf(IllegalStateException.class);

        jdbcTemplate.update("UPDATE coupons SET issued_quantity = 0 WHERE id = ?", coupon.getId());   // 원인 해결

        issuer.issue(coupon.getId(), user.getId());
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
        assertThat(stock.getStock(coupon.getId())).contains(0);
    }

    @Test
    void Redis_Set_이_유실돼_DB_에서_중복이_걸리면_재고만_되돌리고_ALREADY_ISSUED() {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        issuer.issue(coupon.getId(), user.getId());
        redisTemplate.opsForSet().remove(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId()));   // 유실 흉내

        assertThatThrownBy(() -> issuer.issue(coupon.getId(), user.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);

        assertThat(stock.getStock(coupon.getId())).as("재고 원복").contains(4);
        assertThat(redisTemplate.opsForSet().isMember(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId())))
                .as("DB 에 이력이 있으므로 Set 에는 남긴다").isTrue();
    }

    @Test
    void 재고가_적재되지_않았으면_COUPON_STOCK_NOT_READY() {
        Instant now = Instant.now();
        Coupon coupon = couponRepository.save(Coupon.create("미적재", 5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));

        assertThatThrownBy(() -> issuer.issue(coupon.getId(), newUser().getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_STOCK_NOT_READY);
    }

    @Test
    void Redis_가_비워져도_DB_기준으로_재동기화하면_이어서_정확히_발급된다() {
        Coupon coupon = openCoupon(3);
        User first = newUser();
        issuer.issue(coupon.getId(), first.getId());
        redisTemplate.delete(redisTemplate.keys("event:*"));   // Redis 데이터 유실

        var synced = couponService.syncStock(coupon.getId());

        assertThat(synced.redisStock()).isEqualTo(2);
        assertThat(synced.redisIssuedCount()).isEqualTo(1);
        assertThatThrownBy(() -> issuer.issue(coupon.getId(), first.getId()))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
        issuer.issue(coupon.getId(), newUser().getId());
        issuer.issue(coupon.getId(), newUser().getId());
        assertThatThrownBy(() -> issuer.issue(coupon.getId(), newUser().getId()))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_SOLD_OUT);
        assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(3);
    }

    private Coupon openCoupon(int total) {
        Instant now = Instant.now();
        Coupon coupon = couponRepository.save(Coupon.create("Redis 쿠폰", total, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));
        stock.load(coupon, List.of());
        return coupon;
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "redis-" + UUID.randomUUID(), "사용자", null));
    }
}

package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.coupon.issue.async.AsyncCouponIssueRequester;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.reconcile.CouponStockReconciler;
import com.couponrush.coupon.reconcile.StockReconciliationResult;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.IssueRequestStatus;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redis 재고 ↔ DB 발급 대조 점검 (불일치 감지만, 수정하지 않는다) */
@IntegrationTest
class CouponStockReconcilerTest {

    @Autowired CouponStockReconciler reconciler;
    @Autowired AsyncCouponIssueRequester requester;
    @Autowired CouponIssueService couponIssueService;
    @Autowired RedisCouponStock redisStock;
    @Autowired CouponRepository couponRepository;
    @Autowired IssueRequestRepository issueRequestRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuthService authService;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    @Test
    void 정상_발급만_있으면_일치한다() {
        Coupon coupon = openCoupon(5);
        List<String> requestIds = List.of(
                requester.request(coupon.getId(), newUser().getId()),
                requester.request(coupon.getId(), newUser().getId()));
        await().atMost(Duration.ofSeconds(15)).until(() ->
                issueRequestRepository.countByCouponIdAndStatus(coupon.getId(), IssueRequestStatus.COMPLETED) == requestIds.size());

        StockReconciliationResult result = reconciler.reconcile(coupon.getId());

        assertThat(result.consistent()).as(result.problems().toString()).isTrue();
        assertThat(result.redisRemaining()).isEqualTo(3);
        assertThat(result.dbIssuedCount()).isEqualTo(2);
        assertThat(result.redisIssuedCount()).isEqualTo(2);
    }

    @Test
    void Redis_차감_직후_서버가_죽은_유령_발급을_찾아낸다() {
        Coupon coupon = openCoupon(5);
        User ghost = newUser();
        redisStock.tryIssue(coupon.getId(), ghost.getId(), Instant.now());   // Lua 성공 → (발행 전에 서버 종료) → DB 없음

        StockReconciliationResult result = reconciler.reconcile(coupon.getId());

        assertThat(result.consistent()).isFalse();
        assertThat(result.onlyInRedisCount()).isEqualTo(1);
        assertThat(result.onlyInRedisSample()).containsExactly(ghost.getId());
        assertThat(result.problems()).anyMatch(p -> p.contains("남은 재고(4) + DB 발급(0) ≠ 총 수량(5)"));
        assertThat(result.problems()).anyMatch(p -> p.contains("유령 발급"));
    }

    @Test
    void DB_에만_있는_발급을_찾아낸다() {
        Coupon coupon = openCoupon(5);
        User user = newUser();
        couponIssueService.issue(coupon.getId(), user.getId());   // 정상 발급 (Redis 4, DB 1)
        redisTemplate.opsForSet().remove(RedisCouponStock.issuedKey(coupon.getId()), String.valueOf(user.getId()));   // Set 유실

        StockReconciliationResult result = reconciler.reconcile(coupon.getId());

        assertThat(result.consistent()).isFalse();
        assertThat(result.onlyInDbSample()).containsExactly(user.getId());
        assertThat(result.problems()).anyMatch(p -> p.contains("DB 에만 있는 사용자 1명"));
    }

    @Test
    void DB_카운터와_발급_행이_다르면_찾아낸다() {
        Coupon coupon = openCoupon(5);
        couponIssueService.issue(coupon.getId(), newUser().getId());
        jdbcTemplate.update("UPDATE coupons SET issued_quantity = 3 WHERE id = ?", coupon.getId());

        StockReconciliationResult result = reconciler.reconcile(coupon.getId());

        assertThat(result.problems()).anyMatch(p -> p.contains("issued_quantity=3") && p.contains("DB 발급 행(1)"));
    }

    @Test
    void Redis_키가_없으면_수량_비교는_건너뛰고_적재되지_않았다고_알려준다() {
        Instant now = Instant.now();
        Coupon coupon = couponRepository.save(Coupon.create("미적재", 5, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));

        StockReconciliationResult result = reconciler.reconcile(coupon.getId());

        assertThat(result.redisLoaded()).isFalse();
        assertThat(result.consistent()).isTrue();   // DB 내부(카운터 = 행)는 맞음
    }

    @Test
    void 정기_점검은_종료된_이벤트만_보고_불일치만_돌려준다() {
        Instant now = Instant.now();
        Coupon endedBroken = ConcurrencyTestSupport.saveAndLoad(couponRepository, redisStock,
                Coupon.create("종료(불일치)", 5, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(10))));
        Coupon endedOk = ConcurrencyTestSupport.saveAndLoad(couponRepository, redisStock,
                Coupon.create("종료(정상)", 5, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(10))));
        Coupon ongoingBroken = openCoupon(5);
        redisStock.tryIssue(endedBroken.getId(), newUser().getId(), now.minus(Duration.ofHours(1)));   // 기간 중 판정 → 유령
        redisStock.tryIssue(ongoingBroken.getId(), newUser().getId(), now);                           // 진행 중 (점검 대상 아님)

        List<StockReconciliationResult> inconsistent = reconciler.reconcileRecentlyEnded();

        assertThat(inconsistent).extracting(StockReconciliationResult::couponId)
                .contains(endedBroken.getId())
                .doesNotContain(endedOk.getId(), ongoingBroken.getId());
        assertThat(inconsistent).allMatch(StockReconciliationResult::eventEnded);
    }

    @Test
    void 관리자_API_로_조회할_수_있고_일반_사용자는_403() throws Exception {
        Coupon coupon = openCoupon(5);
        redisStock.tryIssue(coupon.getId(), newUser().getId(), Instant.now());
        User admin = newUser();
        String adminToken = authService.login(admin.getId(), Role.ADMIN).accessToken();
        String userToken = authService.login(newUser().getId(), Role.USER).accessToken();

        mockMvc.perform(get("/api/admin/coupons/{id}/reconciliation", coupon.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(false))
                .andExpect(jsonPath("$.onlyInRedisCount").value(1))
                .andExpect(jsonPath("$.problems").isArray());

        mockMvc.perform(get("/api/admin/coupons/{id}/reconciliation", coupon.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    private Coupon openCoupon(int total) {
        return ConcurrencyTestSupport.saveOpenCoupon(couponRepository, redisStock, total);
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "reconcile-" + UUID.randomUUID(), "사용자", null));
    }
}

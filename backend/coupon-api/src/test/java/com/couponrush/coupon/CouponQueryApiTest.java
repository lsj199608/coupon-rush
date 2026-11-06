package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이벤트(쿠폰) 조회 API — 프론트 이벤트 목록/상세 화면용 */
@IntegrationTest
class CouponQueryApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired CouponRepository couponRepository;
    @Autowired RedisCouponStock redisStock;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
    }

    @Test
    void 목록은_로그인_없이_조회되고_오픈_시각_최신순으로_상태와_함께_온다() throws Exception {
        Instant now = Instant.now();
        Coupon ended = save("종료", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)));
        Coupon open = save("진행 중", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        Coupon upcoming = save("오픈 전", now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));

        mockMvc.perform(get("/api/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(upcoming.getId()))
                .andExpect(jsonPath("$[0].status").value("UPCOMING"))
                .andExpect(jsonPath("$[1].id").value(open.getId()))
                .andExpect(jsonPath("$[1].status").value("OPEN"))
                .andExpect(jsonPath("$[2].id").value(ended.getId()))
                .andExpect(jsonPath("$[2].status").value("ENDED"))
                .andExpect(jsonPath("$[0].serverTime").value(notNullValue()));
    }

    @Test
    void 남은_수량은_Redis_재고_기준이다_DB_카운터가_아직_따라오지_않아도() throws Exception {
        Coupon coupon = save("진행 중", Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));
        redisStock.tryIssue(coupon.getId(), newUser().getId(), Instant.now());   // Redis 만 차감 (컨슈머 처리 전 상태)

        mockMvc.perform(get("/api/coupons/{id}", coupon.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuantity").value(9))
                .andExpect(jsonPath("$.issuedQuantity").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/coupons"))
                .andExpect(jsonPath("$[0].remainingQuantity").value(9));
    }

    @Test
    void Redis_에_재고가_없으면_DB_기준_수량을_쓴다() throws Exception {
        Instant now = Instant.now();
        Coupon coupon = couponRepository.save(Coupon.create("미적재", 7, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));

        mockMvc.perform(get("/api/coupons/{id}", coupon.getId()))
                .andExpect(jsonPath("$.remainingQuantity").value(7));
    }

    @Test
    void 이벤트가_없으면_빈_목록() throws Exception {
        mockMvc.perform(get("/api/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private Coupon save(String name, Instant start, Instant end) {
        return ConcurrencyTestSupport.saveAndLoad(couponRepository, redisStock, Coupon.create(name, 10, start, end));
    }

    private User newUser() {
        return userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "query-" + UUID.randomUUID(), "사용자", null));
    }
}

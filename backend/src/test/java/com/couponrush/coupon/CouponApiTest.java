package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class CouponApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    User user;
    String userToken;
    String adminToken;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "api-" + UUID.randomUUID(), "사용자", null));
        User admin = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "admin-" + UUID.randomUUID(), "관리자", null));
        userToken = authService.login(user.getId(), Role.USER).accessToken();
        adminToken = authService.login(admin.getId(), Role.ADMIN).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM coupon_issues");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    @Nested
    class 쿠폰_생성_관리자 {

        @Test
        void 관리자는_쿠폰을_만들_수_있다() throws Exception {
            createCoupon(adminToken, validRequest(100))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/coupons/")))
                    .andExpect(jsonPath("$.name").value("오픈 기념 쿠폰"))
                    .andExpect(jsonPath("$.totalQuantity").value(100))
                    .andExpect(jsonPath("$.remainingQuantity").value(100));
        }

        @Test
        void 일반_사용자는_403_FORBIDDEN() throws Exception {
            createCoupon(userToken, validRequest(100))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        void 토큰이_없으면_401() throws Exception {
            mockMvc.perform(post("/api/admin/coupons").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(100))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 잘못된_값이면_400_INVALID_INPUT_과_필드별_사유() throws Exception {
            Instant now = Instant.now();
            Map<String, Object> request = Map.of(
                    "name", "",
                    "totalQuantity", 0,
                    "issueStartAt", now.plus(Duration.ofHours(2)).toString(),
                    "issueEndAt", now.toString());

            createCoupon(adminToken, request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("totalQuantity")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("issuePeriodValid")));
        }

        @Test
        void JSON_형식이_틀리면_400_INVALID_INPUT() throws Exception {
            mockMvc.perform(post("/api/admin/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"issueStartAt\": \"어제\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Nested
    class 쿠폰_조회와_발급 {

        @Test
        void 쿠폰_정보는_로그인_없이_조회된다() throws Exception {
            Coupon coupon = openCoupon(5);

            mockMvc.perform(get("/api/coupons/{id}", coupon.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingQuantity").value(5));
        }

        @Test
        void 없는_쿠폰_조회는_404_COUPON_NOT_FOUND() throws Exception {
            mockMvc.perform(get("/api/coupons/{id}", 999_999))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
        }

        @Test
        void 숫자가_아닌_ID_는_400_INVALID_INPUT() throws Exception {
            mockMvc.perform(get("/api/coupons/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        void 발급은_로그인이_필요하다() throws Exception {
            Coupon coupon = openCoupon(5);

            mockMvc.perform(post("/api/coupons/{id}/issue", coupon.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 발급하면_201_이고_남은수량이_줄고_내_쿠폰에_보인다() throws Exception {
            Coupon coupon = openCoupon(5);

            issue(coupon.getId(), userToken)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.couponId").value(coupon.getId()))
                    .andExpect(jsonPath("$.issueId").isNumber());

            mockMvc.perform(get("/api/coupons/{id}", coupon.getId()))
                    .andExpect(jsonPath("$.issuedQuantity").value(1))
                    .andExpect(jsonPath("$.remainingQuantity").value(4));

            mockMvc.perform(get("/api/users/me/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].couponName").value("API 테스트 쿠폰"));
        }

        @Test
        void 두_번째_요청은_409_COUPON_ALREADY_ISSUED() throws Exception {
            Coupon coupon = openCoupon(5);
            issue(coupon.getId(), userToken).andExpect(status().isCreated());

            issue(coupon.getId(), userToken)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"));
        }

        @Test
        void 소진되면_409_COUPON_SOLD_OUT() throws Exception {
            Coupon coupon = openCoupon(1);
            issue(coupon.getId(), adminToken).andExpect(status().isCreated());

            issue(coupon.getId(), userToken)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));
        }

        @Test
        void 기간이_아니면_400_COUPON_NOT_IN_PERIOD() throws Exception {
            Instant now = Instant.now();
            Coupon coupon = couponRepository.save(Coupon.create("예정", 5, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))));

            issue(coupon.getId(), userToken)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_IN_PERIOD"));
        }
    }

    private Map<String, Object> validRequest(int totalQuantity) {
        Instant now = Instant.now();
        return Map.of(
                "name", "오픈 기념 쿠폰",
                "totalQuantity", totalQuantity,
                "issueStartAt", now.toString(),
                "issueEndAt", now.plus(Duration.ofDays(1)).toString());
    }

    private ResultActions createCoupon(String token, Object body) throws Exception {
        return mockMvc.perform(post("/api/admin/coupons")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions issue(Long couponId, String token) throws Exception {
        return mockMvc.perform(post("/api/coupons/{id}/issue", couponId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Coupon openCoupon(int totalQuantity) {
        Instant now = Instant.now();
        return couponRepository.save(Coupon.create("API 테스트 쿠폰", totalQuantity,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))));
    }
}

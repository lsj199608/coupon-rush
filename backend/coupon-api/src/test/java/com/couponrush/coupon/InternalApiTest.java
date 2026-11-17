package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.auth.internal.InternalApiProperties;
import com.couponrush.auth.internal.InternalServiceAuthenticationFilter;
import com.couponrush.coupon.stock.RedisCouponStock;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static com.couponrush.auth.internal.InternalServiceAuthenticationFilter.TOKEN_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 간 내부 API (/internal/**, X-Internal-Token). 테스트 토큰은 IntegrationTest 의 INTERNAL_API_TOKEN=test-internal-token.
 * 핵심: 토큰이 맞을 때만, 그리고 사용자 JWT 로는 절대 열리지 않는다 (경로의 userId 를 그대로 믿는 API 라서).
 */
@IntegrationTest
class InternalApiTest {

    static final String TOKEN = "test-internal-token";

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository couponIssueRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisCouponStock redisStock;

    User owner;
    User other;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "internal-" + UUID.randomUUID(), "주인", null));
        other = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "internal-" + UUID.randomUUID(), "다른 사람", null));
        Coupon coupon = ConcurrencyTestSupport.saveOpenCoupon(couponRepository, redisStock, 10);
        couponIssueRepository.save(new CouponIssue(coupon.getId(), owner.getId(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    private String path(User user) {
        return "/internal/users/" + user.getId() + "/coupons";
    }

    @Test
    void 토큰이_맞으면_경로의_사용자_쿠폰을_돌려준다() throws Exception {
        mockMvc.perform(get(path(owner)).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].couponName").exists());

        mockMvc.perform(get(path(other)).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 토큰이_없거나_틀리면_401() throws Exception {
        mockMvc.perform(get(path(owner)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(path(owner)).header(TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path(owner)).header(TOKEN_HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 사용자_JWT_로는_관리자라도_들어올_수_없다_403() throws Exception {
        String userToken = authService.login(other.getId(), Role.USER).accessToken();
        String adminToken = authService.login(owner.getId(), Role.ADMIN).accessToken();

        mockMvc.perform(get(path(owner)).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path(owner)).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 내부_토큰으로_사용자_API_는_쓸_수_없다() throws Exception {
        mockMvc.perform(get("/api/users/me/coupons").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/coupons/1/reconciliation").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰_설정이_비어_있으면_아무것도_통과하지_않는다() throws Exception {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter(new InternalApiProperties(""));
        for (String sent : new String[]{"", " ", "anything"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/users/1/coupons");
            request.addHeader(TOKEN_HEADER, sent);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(SecurityContextHolder.getContext().getAuthentication()).as("보낸 값: '%s'", sent).isNull();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void 필터는_내부_경로에서만_동작한다() throws Exception {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter(new InternalApiProperties(TOKEN));
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me/coupons");
        ((MockHttpServletRequest) request).addHeader(TOKEN_HEADER, TOKEN);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        SecurityContextHolder.clearContext();
    }
}

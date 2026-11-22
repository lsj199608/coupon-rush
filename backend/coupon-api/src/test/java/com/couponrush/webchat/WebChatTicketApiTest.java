package com.couponrush.webchat;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.couponrush.webchat.contract.WebChatTicket;
import com.couponrush.webchat.contract.WebChatTickets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/web-chat/tickets — 채팅방 연결용 1회용 티켓 (7단계 D2). 티켓을 쓰는 쪽(web-chat-service)은 그 모듈 테스트에서. */
@IntegrationTest
class WebChatTicketApiTest {

    private static final String URL = "/api/web-chat/tickets";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    User user;
    String token;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "web-chat-" + UUID.randomUUID(), "채팅회원", null));
        token = authService.login(user.getId(), Role.USER).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("web-chat:*"));
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    @Test
    void 로그인하면_30초짜리_티켓을_받고_Redis_에는_해시_키로_회원_ID_와_닉네임이_저장된다() throws Exception {
        Instant before = Instant.now();

        JsonNode body = issue(token);

        String ticket = body.get("ticket").asText();
        assertThat(WebChatTickets.isWellFormed(ticket)).isTrue();
        Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
        assertThat(expiresAt).isBetween(before.plus(WebChatTickets.TTL).minusSeconds(1), Instant.now().plus(WebChatTickets.TTL));

        String key = WebChatTickets.redisKey(ticket);
        WebChatTicket saved = objectMapper.readValue(redisTemplate.opsForValue().get(key), WebChatTicket.class);
        assertThat(saved.userId()).isEqualTo(user.getId());
        assertThat(saved.nickname()).isEqualTo("채팅회원");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, WebChatTickets.TTL.toSeconds());
    }

    @Test
    void 티켓_원문은_Redis_어디에도_없다() throws Exception {
        String ticket = issue(token).get("ticket").asText();

        assertThat(redisTemplate.keys("*" + ticket + "*")).isEmpty();
        for (String key : redisTemplate.keys("web-chat:*")) {
            assertThat(redisTemplate.opsForValue().get(key)).doesNotContain(ticket);
        }
    }

    @Test
    void 받을_때마다_다른_티켓이다() throws Exception {
        String first = issue(token).get("ticket").asText();
        String second = issue(token).get("ticket").asText();

        assertThat(first).isNotEqualTo(second);
        assertThat(redisTemplate.keys("web-chat:ticket:*")).hasSize(2);
    }

    @Test
    void 티켓은_캐시에_남지_않는다() throws Exception {
        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void 로그인이_필요하다() throws Exception {
        mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
        assertThat(redisTemplate.keys("web-chat:*")).isEmpty();
    }

    @Test
    void 회원이_없으면_404_이고_티켓을_만들지_않는다() throws Exception {
        userRepository.deleteById(user.getId());

        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(redisTemplate.keys("web-chat:*")).isEmpty();
    }

    @Test
    void 닉네임은_발급_시점_값이다() throws Exception {
        String ticket = issue(token).get("ticket").asText();
        jdbcTemplate.update("UPDATE users SET nickname = '바뀐닉' WHERE id = ?", user.getId());

        WebChatTicket saved = objectMapper.readValue(
                redisTemplate.opsForValue().get(WebChatTickets.redisKey(ticket)), WebChatTicket.class);
        assertThat(saved.nickname()).isEqualTo("채팅회원");
        assertThat(Duration.between(saved.issuedAt(), Instant.now())).isLessThan(Duration.ofMinutes(1));
    }

    private JsonNode issue(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post(URL).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

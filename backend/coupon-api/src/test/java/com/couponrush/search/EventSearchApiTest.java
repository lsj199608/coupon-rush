package com.couponrush.search;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.llm.LlmRateLimiter;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestRepository;
import com.couponrush.llm.LlmRequestService;
import com.couponrush.llm.LlmRequestStatus;
import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchStatus;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmRequestType;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llm.contract.LlmUsageKeys;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자연어 검색 API (실제 Postgres / Redis / Kafka, llm-service 없음).
 * 테스트가 llm-service 대신 llm-results 를 발행해 AI 조건 · 실패를 흉내 낸다.
 */
@IntegrationTest
class EventSearchApiTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String LOCAL_IP = "127.0.0.1";   // MockMvc 기본 remoteAddr

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired LlmRequestRepository llmRequestRepository;
    @Autowired LlmRateLimiter rateLimiter;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    final Instant now = Instant.now();
    User user;
    String userToken;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "search-user-" + UUID.randomUUID(), "회원", null));
        userToken = authService.login(user.getId(), Role.USER).accessToken();
        couponRepository.save(Coupon.create("커피 할인", 100, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1))));
        couponRepository.save(Coupon.create("커피 예정", 100, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))));
        couponRepository.save(Coupon.create("치킨 반값", 100, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1))));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM llm_requests");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("llm:*"));
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private ResultActions search(String query, String token) throws Exception {
        var builder = post("/api/coupons/search").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query)));
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    private UUID accept(String query, String token) throws Exception {
        String body = search(query, token).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("requestId").asText());
    }

    private JsonNode poll(UUID requestId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/coupons/search/{id}", requestId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 결과 메시지가 컨슈머를 거쳐 반영될 때까지 폴링 */
    private JsonNode awaitFinished(UUID requestId) {
        return await().atMost(WAIT).until(() -> poll(requestId), body -> !"PENDING".equals(body.get("status").asText()));
    }

    private void publish(LlmResultMessage message) throws Exception {
        kafkaTemplate.send(LlmTopics.RESULTS, message.requestId().toString(), objectMapper.writeValueAsString(message)).get();
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("events").forEach(event -> names.add(event.get("name").asText()));
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    // ───────────────────────── 접수 ─────────────────────────

    @Nested
    class 접수 {

        @Test
        void 비로그인도_검색할_수_있고_202_PENDING_과_검색_요청_메시지가_생긴다() throws Exception {
            try (Consumer<String, String> consumer = consumerFactory.createConsumer("search-test-" + UUID.randomUUID(), null)) {
                consumer.subscribe(List.of(LlmTopics.REQUESTS));

                String response = search("  진행 중인   커피 ", null)
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/coupons/search/")))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.query").value("진행 중인 커피"))
                        .andExpect(jsonPath("$.events").isEmpty())
                        .andReturn().getResponse().getContentAsString();
                UUID requestId = UUID.fromString(objectMapper.readTree(response).get("requestId").asText());

                LlmRequest saved = llmRequestRepository.findById(requestId).orElseThrow();
                assertThat(saved.getType()).isEqualTo(LlmRequestType.EVENT_SEARCH);
                assertThat(saved.getRequestedBy()).isEqualTo(LlmRequestService.ANONYMOUS);
                assertThat(Duration.between(saved.getCreatedAt(), saved.getDeadlineAt()))
                        .as("검색 마감은 문구 생성(60초)보다 짧다").isEqualTo(Duration.ofSeconds(15));

                ConsumerRecord<String, String> record = await().atMost(WAIT).until(() -> {
                    for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                        if (requestId.toString().equals(r.key())) {
                            return r;
                        }
                    }
                    return null;
                }, r -> r != null);
                LlmRequestMessage message = objectMapper.readValue(record.value(), LlmRequestMessage.class);
                assertThat(message.type()).isEqualTo(LlmRequestType.EVENT_SEARCH);
                assertThat(message.eventSearch().query()).isEqualTo("진행 중인 커피");
                assertThat(message.eventCopy()).isNull();
                assertThat(message.deadline()).isEqualTo(saved.getDeadlineAt());
            }
        }

        @Test
        void 결과가_오기_전에는_PENDING_이고_결과_목록은_비어_있다() throws Exception {
            UUID requestId = accept("커피", null);

            JsonNode body = poll(requestId);

            assertThat(body.get("status").asText()).isEqualTo("PENDING");
            assertThat(body.get("deadlineAt").isNull()).isFalse();
            assertThat(body.get("events")).isEmpty();
        }

        @Test
        void 로그인했으면_회원_ID_로_기록하고_한도도_회원별로_센다() throws Exception {
            for (int i = 0; i < 10; i++) {
                rateLimiter.acquireSearch("ip:" + LOCAL_IP, Instant.now());   // 같은 IP 의 비로그인 한도는 소진
            }

            UUID requestId = accept("커피", userToken);

            assertThat(llmRequestRepository.findById(requestId).orElseThrow().getRequestedBy()).isEqualTo(user.getId());
        }

        @Test
        void 질문이_비었거나_200자를_넘으면_400() throws Exception {
            search("   ", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            search("가".repeat(201), null).andExpect(status().isBadRequest());
            assertThat(llmRequestRepository.count()).isZero();
        }
    }

    // ───────────────────────── AI 조건 ─────────────────────────

    @Nested
    class AI_조건 {

        @Test
        void 조건이_오면_COMPLETED_와_DB_에서_조건대로_찾은_이벤트() throws Exception {
            UUID requestId = accept("진행 중인 커피", null);
            EventSearchFilter filter = new EventSearchFilter(List.of("커피"), Set.of(EventSearchStatus.OPEN),
                    null, null, null, null, false);

            publish(LlmResultMessage.searchCompleted(requestId, filter, "fake", 10, 5, false, 3, Instant.now()));
            JsonNode body = awaitFinished(requestId);

            assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(names(body)).containsExactly("커피 할인");
            assertThat(texts(body.get("filter").get("keywords"))).containsExactly("커피");
            assertThat(texts(body.get("filter").get("statuses"))).containsExactly("OPEN");
            assertThat(body.get("fallbackReason").isNull()).isTrue();
            assertThat(objectMapper.readValue(llmRequestRepository.findById(requestId).orElseThrow().getResult(),
                    EventSearchFilter.class)).as("result 컬럼에는 검색 조건을 저장").isEqualTo(filter);
        }

        @Test
        void 빈_조건이면_조건_없이_전부() throws Exception {
            UUID requestId = accept("쿠폰 보여줘", null);

            publish(LlmResultMessage.searchCompleted(requestId, EventSearchFilter.empty(), "fake", 10, 5, false, 3, Instant.now()));
            JsonNode body = awaitFinished(requestId);

            assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(names(body)).containsExactly("커피 예정", "치킨 반값", "커피 할인");
        }

        @Test
        void 관리자_LLM_조회_API_로_검색_요청을_열어도_후보는_비어_있다() throws Exception {
            String adminToken = authService.login(user.getId(), Role.ADMIN).accessToken();
            UUID requestId = accept("커피", adminToken);
            publish(LlmResultMessage.searchCompleted(requestId, EventSearchFilter.empty(), "fake", 10, 5, false, 3, Instant.now()));
            awaitFinished(requestId);

            mockMvc.perform(get("/api/admin/llm/requests/{id}", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("EVENT_SEARCH"))
                    .andExpect(jsonPath("$.candidates").isEmpty());
        }
    }

    // ───────────────────────── 키워드 검색으로 대신 ─────────────────────────

    @Nested
    class 키워드_검색_fallback {

        @Test
        void LLM_이_실패하면_질문의_단어_중_하나라도_들어_있는_이벤트() throws Exception {
            UUID requestId = accept("커피 이벤트 알려줘", null);

            publish(LlmResultMessage.failed(requestId, LlmFailureReason.INVALID_OUTPUT, "fake", 10, 5, 3, Instant.now()));
            JsonNode body = awaitFinished(requestId);

            assertThat(body.get("status").asText()).isEqualTo("FALLBACK");
            assertThat(body.get("fallbackReason").asText()).isEqualTo("LLM_UNAVAILABLE");
            assertThat(texts(body.get("keywords"))).containsExactly("커피");
            assertThat(names(body)).containsExactly("커피 예정", "커피 할인");
            assertThat(body.get("filter").isNull()).isTrue();
        }

        @Test
        void 마감이_지나면_TIMEOUT_으로_확정하고_키워드_검색() throws Exception {
            UUID requestId = accept("치킨", null);
            jdbcTemplate.update("UPDATE llm_requests SET deadline_at = now() - interval '1 second' WHERE request_id = ?", requestId);

            JsonNode body = poll(requestId);

            assertThat(body.get("status").asText()).isEqualTo("FALLBACK");
            assertThat(body.get("fallbackReason").asText()).isEqualTo("TIMEOUT");
            assertThat(names(body)).containsExactly("치킨 반값");
            LlmRequest saved = llmRequestRepository.findById(requestId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(LlmRequestStatus.FAILED);
            assertThat(saved.getFailureReason()).isEqualTo(LlmFailureReason.TIMEOUT);
        }

        @Test
        void 오늘_예산이_소진됐으면_요청을_만들지_않고_바로_200_FALLBACK() throws Exception {
            redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "50000");

            search("커피", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FALLBACK"))
                    .andExpect(jsonPath("$.fallbackReason").value("BUDGET_EXCEEDED"))
                    .andExpect(jsonPath("$.requestId").doesNotExist())
                    .andExpect(jsonPath("$.events.length()").value(2));
            assertThat(llmRequestRepository.count()).isZero();
        }

        @Test
        void 한도를_넘으면_요청을_만들지_않고_바로_200_FALLBACK() throws Exception {
            for (int i = 0; i < 10; i++) {
                accept("치킨 " + i, null);
            }

            search("치킨", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FALLBACK"))
                    .andExpect(jsonPath("$.fallbackReason").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.events[0].name").value("치킨 반값"));
            assertThat(llmRequestRepository.count()).isEqualTo(10);
        }
    }

    @Nested
    class 조회 {

        @Test
        void 없는_요청과_문구_생성_요청은_404() throws Exception {
            mockMvc.perform(get("/api/coupons/search/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_SEARCH_NOT_FOUND"));

            UUID copyRequest = UUID.randomUUID();
            llmRequestRepository.save(LlmRequest.pending(copyRequest, LlmRequestType.EVENT_COPY, user.getId(), "{}",
                    Instant.now(), Instant.now().plusSeconds(60)));
            mockMvc.perform(get("/api/coupons/search/{id}", copyRequest)).andExpect(status().isNotFound());
        }
    }
}

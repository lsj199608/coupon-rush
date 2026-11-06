package com.couponrush.llm;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.coupon.issue.async.CouponIssueKafkaConfig;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
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
import java.util.List;
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
 * coupon-api 쪽 LLM 요청 흐름 (실제 Postgres / Redis / Kafka). llm-service 없이 검증한다:
 * 테스트가 llm-requests 를 직접 읽어 요청 메시지를 확인하고, llm-service 대신 llm-results 를 직접 발행한다.
 */
@IntegrationTest
class LlmRequestApiTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired LlmRequestRepository llmRequestRepository;
    @Autowired LlmRequestTimeouts timeouts;
    @Autowired LlmRateLimiter rateLimiter;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

    User admin;
    String adminToken;
    String otherAdminToken;
    String userToken;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "llm-admin-" + UUID.randomUUID(), "관리자", null));
        User otherAdmin = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "llm-admin2-" + UUID.randomUUID(), "관리자2", null));
        User user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "llm-user-" + UUID.randomUUID(), "회원", null));
        adminToken = authService.login(admin.getId(), Role.ADMIN).accessToken();
        otherAdminToken = authService.login(otherAdmin.getId(), Role.ADMIN).accessToken();
        userToken = authService.login(user.getId(), Role.USER).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM llm_requests");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("llm:*"));
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static final String VALID_BODY = """
            {"keywords":"겨울 한정 핫초코 50%% 할인","tone":"URGENT","totalQuantity":300,
             "issueStartAt":"2030-01-02T10:00:00Z","issueEndAt":"2030-01-02T12:00:00Z"}
            """.formatted();

    private ResultActions requestEventCopy(String token, String body) throws Exception {
        var builder = post("/api/admin/llm/event-copy").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    private UUID accept(String token) throws Exception {
        String response = requestEventCopy(token, VALID_BODY).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
    }

    private JsonNode poll(UUID requestId, String token) throws Exception {
        String body = mockMvc.perform(get("/api/admin/llm/requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void publishResult(String key, String value) throws Exception {
        kafkaTemplate.send(LlmTopics.RESULTS, key, value).get();
    }

    private void publishResult(LlmResultMessage message) throws Exception {
        publishResult(message.requestId().toString(), objectMapper.writeValueAsString(message));
    }

    private static List<EventCopyCandidate> candidates() {
        return List.of(
                new EventCopyCandidate("선착순 300명! 핫초코 반값", "오늘 10시, 딱 300잔만 반값입니다."),
                new EventCopyCandidate("겨울엔 핫초코", "따뜻한 핫초코를 50% 할인된 가격에 만나 보세요."),
                new EventCopyCandidate("두 시간의 기회", "10시부터 12시까지, 수량이 끝나면 종료됩니다."));
    }

    private static LlmResultMessage completed(UUID requestId) {
        return LlmResultMessage.completed(requestId, candidates(), "fake", 120, 80, false, 15, Instant.now());
    }

    private String statusOf(UUID requestId) {
        return jdbcTemplate.queryForObject("SELECT status FROM llm_requests WHERE request_id = ?", String.class, requestId);
    }

    // ───────────────────────── tests ─────────────────────────

    @Nested
    class 권한과_입력_검증 {

        @Test
        void 로그인하지_않으면_401() throws Exception {
            requestEventCopy(null, VALID_BODY).andExpect(status().isUnauthorized());
        }

        @Test
        void 일반_회원은_403() throws Exception {
            requestEventCopy(userToken, VALID_BODY)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(llmRequestRepository.count()).isZero();
        }

        @Test
        void 잘못된_입력은_400_이고_한도를_쓰지_않는다() throws Exception {
            requestEventCopy(adminToken, """
                    {"keywords":"  ","tone":"URGENT","totalQuantity":300}
                    """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            requestEventCopy(adminToken, """
                    {"keywords":"봄","tone":"SAD","totalQuantity":300}
                    """).andExpect(status().isBadRequest());   // 없는 톤
            requestEventCopy(adminToken, """
                    {"keywords":"봄","tone":"FRIENDLY","totalQuantity":10,
                     "issueStartAt":"2030-01-02T12:00:00Z","issueEndAt":"2030-01-02T10:00:00Z"}
                    """).andExpect(status().isBadRequest());
            requestEventCopy(adminToken, """
                    {"keywords":"%s","tone":"FRIENDLY","totalQuantity":10}
                    """.formatted("가".repeat(201))).andExpect(status().isBadRequest());

            assertThat(llmRequestRepository.count()).isZero();
            assertThat(redisTemplate.keys("llm:rate:*")).isEmpty();
        }

        @Test
        void 발급_기간은_비워도_된다() throws Exception {
            requestEventCopy(adminToken, """
                    {"keywords":"봄맞이 할인","tone":"FRIENDLY","totalQuantity":10}
                    """).andExpect(status().isAccepted());
        }
    }

    @Nested
    class 요청_접수 {

        @Test
        void 접수하면_202_와_Location_을_주고_PENDING_행과_요청_메시지가_생긴다() throws Exception {
            try (Consumer<String, String> consumer = consumerFactory.createConsumer("llm-test-" + UUID.randomUUID(), null)) {
                consumer.subscribe(List.of(LlmTopics.REQUESTS));

                String response = requestEventCopy(adminToken, VALID_BODY)
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/admin/llm/requests/")))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn().getResponse().getContentAsString();
                UUID requestId = UUID.fromString(objectMapper.readTree(response).get("requestId").asText());

                // DB: 발행 전에 커밋된 PENDING 행
                LlmRequest saved = llmRequestRepository.findById(requestId).orElseThrow();
                assertThat(saved.getStatus()).isEqualTo(LlmRequestStatus.PENDING);
                assertThat(saved.getRequestedBy()).isEqualTo(admin.getId());
                assertThat(Duration.between(saved.getCreatedAt(), saved.getDeadlineAt())).isEqualTo(Duration.ofSeconds(60));
                assertThat(objectMapper.readTree(saved.getInput()).get("keywords").asText()).isEqualTo("겨울 한정 핫초코 50% 할인");

                // Kafka: 키 = requestId, 계약 형식, JWT 없음
                ConsumerRecord<String, String> record = await().atMost(WAIT).until(() -> {
                    for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                        if (requestId.toString().equals(r.key())) {
                            return r;
                        }
                    }
                    return null;
                }, r -> r != null);
                LlmRequestMessage message = objectMapper.readValue(record.value(), LlmRequestMessage.class);
                assertThat(message.requestId()).isEqualTo(requestId);
                assertThat(message.requestedBy()).isEqualTo(admin.getId());
                assertThat(message.deadline()).isEqualTo(saved.getDeadlineAt());
                assertThat(message.eventCopy().tone()).isEqualTo(EventCopyTone.URGENT);
                assertThat(message.regenerate()).isFalse();
                assertThat(record.value()).doesNotContain("Bearer").doesNotContain(adminToken);
            }
        }

        @Test
        void 결과가_오기_전에는_PENDING_으로_조회된다() throws Exception {
            UUID requestId = accept(adminToken);

            JsonNode body = poll(requestId, adminToken);

            assertThat(body.get("status").asText()).isEqualTo("PENDING");
            assertThat(body.get("candidates")).isEmpty();
            assertThat(body.get("retryable").isNull()).isTrue();
        }

        @Test
        void 다른_관리자의_요청과_없는_요청은_404() throws Exception {
            UUID requestId = accept(adminToken);

            mockMvc.perform(get("/api/admin/llm/requests/{id}", requestId).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAdminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LLM_REQUEST_NOT_FOUND"));
            mockMvc.perform(get("/api/admin/llm/requests/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/admin/llm/requests/not-a-uuid").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class 결과_반영 {

        @Test
        void 성공_결과가_오면_COMPLETED_와_후보_3개로_조회된다() throws Exception {
            UUID requestId = accept(adminToken);

            publishResult(completed(requestId));

            await().atMost(WAIT).until(() -> "COMPLETED".equals(statusOf(requestId)));
            JsonNode body = poll(requestId, adminToken);
            assertThat(body.get("candidates")).hasSize(3);
            assertThat(body.get("candidates").get(0).get("name").asText()).isEqualTo("선착순 300명! 핫초코 반값");
            assertThat(body.get("model").asText()).isEqualTo("fake");
            assertThat(body.get("inputTokens").asInt()).isEqualTo(120);
            assertThat(body.get("outputTokens").asInt()).isEqualTo(80);
            assertThat(body.get("failureReason").isNull()).isTrue();
            assertThat(body.get("completedAt").isNull()).isFalse();
        }

        @Test
        void 실패_결과가_오면_FAILED_와_사유_재시도_가능_여부로_조회된다() throws Exception {
            UUID retryable = accept(adminToken);
            UUID final_ = accept(adminToken);

            publishResult(LlmResultMessage.failed(retryable, LlmFailureReason.LLM_UNAVAILABLE, null, 0, 0, 10, Instant.now()));
            publishResult(LlmResultMessage.failed(final_, LlmFailureReason.BUDGET_EXCEEDED, null, 0, 0, 1, Instant.now()));

            await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(retryable)) && "FAILED".equals(statusOf(final_)));
            JsonNode first = poll(retryable, adminToken);
            assertThat(first.get("failureReason").asText()).isEqualTo("LLM_UNAVAILABLE");
            assertThat(first.get("retryable").asBoolean()).isTrue();
            assertThat(first.get("candidates")).isEmpty();
            JsonNode second = poll(final_, adminToken);
            assertThat(second.get("failureReason").asText()).isEqualTo("BUDGET_EXCEEDED");
            assertThat(second.get("retryable").asBoolean()).isFalse();
        }

        @Test
        void 읽을_수_없는_결과_메시지는_건너뛰고_다음_메시지는_정상_처리한다() throws Exception {
            UUID requestId = accept(adminToken);
            String key = requestId.toString();   // 같은 키 → 같은 파티션 → 순서대로 처리된다

            publishResult(key, "{not json");
            publishResult(key, """
                    {"schemaVersion":1,"requestId":"%s","status":"FAILED","completedAt":"2030-01-01T00:00:00Z"}
                    """.formatted(requestId));   // 계약 위반: FAILED 인데 사유 없음
            publishResult(completed(requestId));

            await().atMost(WAIT).until(() -> "COMPLETED".equals(statusOf(requestId)));
        }
    }

    @Nested
    class 타임아웃 {

        @Test
        void 마감이_지난_요청은_조회하는_순간_TIMEOUT_이_된다() throws Exception {
            UUID requestId = accept(adminToken);
            jdbcTemplate.update("UPDATE llm_requests SET deadline_at = now() - interval '1 second' WHERE request_id = ?", requestId);

            JsonNode body = poll(requestId, adminToken);

            assertThat(body.get("status").asText()).isEqualTo("FAILED");
            assertThat(body.get("failureReason").asText()).isEqualTo("TIMEOUT");
            assertThat(body.get("retryable").asBoolean()).isTrue();
        }

        @Test
        void 스케줄러는_마감이_지난_PENDING_만_TIMEOUT_으로_바꾼다() throws Exception {
            UUID expired = accept(adminToken);
            UUID alive = accept(adminToken);
            UUID done = accept(adminToken);
            publishResult(completed(done));
            await().atMost(WAIT).until(() -> "COMPLETED".equals(statusOf(done)));
            jdbcTemplate.update("UPDATE llm_requests SET deadline_at = now() - interval '1 second' WHERE request_id IN (?, ?)", expired, done);

            int count = timeouts.sweep();

            assertThat(count).isEqualTo(1);
            assertThat(statusOf(expired)).isEqualTo("FAILED");
            assertThat(statusOf(alive)).isEqualTo("PENDING");
            assertThat(statusOf(done)).isEqualTo("COMPLETED");   // 이미 끝난 요청은 마감이 지나도 그대로
        }

        @Test
        void TIMEOUT_뒤에_도착한_결과는_버린다() throws Exception {
            UUID late = accept(adminToken);
            UUID marker = accept(adminToken);
            jdbcTemplate.update("UPDATE llm_requests SET deadline_at = now() - interval '1 second' WHERE request_id = ?", late);
            timeouts.sweep();

            // 같은 파티션에 "늦은 결과 → 표시용 결과" 순서로 보낸다. 표시용이 반영됐으면 늦은 결과도 이미 처리된 것
            kafkaTemplate.send(LlmTopics.RESULTS, "same-key", objectMapper.writeValueAsString(completed(late))).get();
            kafkaTemplate.send(LlmTopics.RESULTS, "same-key", objectMapper.writeValueAsString(completed(marker))).get();
            await().atMost(WAIT).until(() -> "COMPLETED".equals(statusOf(marker)));

            JsonNode body = poll(late, adminToken);
            assertThat(body.get("status").asText()).isEqualTo("FAILED");
            assertThat(body.get("failureReason").asText()).isEqualTo("TIMEOUT");
            assertThat(body.get("candidates")).isEmpty();
        }
    }

    @Nested
    class 레이트_리밋 {

        @Test
        void 분당_한도를_넘으면_429_이고_행도_메시지도_만들지_않는다() throws Exception {
            Instant now = Instant.now();
            // 분 경계에 걸려도 결정적이도록 이번 분과 다음 분 카운터를 모두 한도까지 채운다
            for (Instant t : List.of(now, now.plus(Duration.ofMinutes(1)))) {
                redisTemplate.opsForValue().set(rateLimiter.minuteKey(admin.getId(), t), "5");
            }

            requestEventCopy(adminToken, VALID_BODY)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("LLM_RATE_LIMITED"));
            assertThat(llmRequestRepository.count()).isZero();
        }

        @Test
        void 일일_한도를_넘으면_429_LLM_DAILY_LIMIT_EXCEEDED() throws Exception {
            Instant now = Instant.now();
            for (Instant t : List.of(now, now.plus(Duration.ofMinutes(1)))) {
                redisTemplate.opsForValue().set(rateLimiter.dayKey(admin.getId(), t), "50");
            }

            requestEventCopy(adminToken, VALID_BODY)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("LLM_DAILY_LIMIT_EXCEEDED"));
        }

        @Test
        void 오늘_토큰_예산이_소진됐으면_429_LLM_BUDGET_EXCEEDED_이고_한도_횟수도_쓰지_않는다() throws Exception {
            Instant now = Instant.now();
            for (Instant t : List.of(now, now.plus(Duration.ofMinutes(1)))) {   // 자정 경계에도 결정적이도록
                redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(t), LlmUsageKeys.TOTAL_TOKENS, "50000");
            }

            requestEventCopy(adminToken, VALID_BODY)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("LLM_BUDGET_EXCEEDED"));
            assertThat(llmRequestRepository.count()).isZero();
            assertThat(redisTemplate.keys("llm:rate:*")).isEmpty();
        }

        @Test
        void 예산이_남아_있으면_접수한다() throws Exception {
            redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "49999");

            requestEventCopy(adminToken, VALID_BODY).andExpect(status().isAccepted());
        }

        @Test
        void 한도는_관리자마다_따로다() throws Exception {
            Instant now = Instant.now();
            for (Instant t : List.of(now, now.plus(Duration.ofMinutes(1)))) {
                redisTemplate.opsForValue().set(rateLimiter.minuteKey(admin.getId(), t), "5");
            }

            requestEventCopy(otherAdminToken, VALID_BODY).andExpect(status().isAccepted());
        }
    }

    @Test
    void 결과_컨슈머는_발급_컨슈머와_그룹_컨테이너_스레드를_공유하지_않는다() {
        MessageListenerContainer llm = listenerRegistry.getListenerContainer(LlmKafkaConfig.RESULT_LISTENER_ID);
        MessageListenerContainer issue = listenerRegistry.getListenerContainer(CouponIssueKafkaConfig.MAIN_LISTENER_ID);

        assertThat(llm).isNotNull().isNotSameAs(issue);
        assertThat(llm.getGroupId()).isEqualTo(LlmKafkaConfig.RESULT_CONSUMER_GROUP).isNotEqualTo(issue.getGroupId());
        assertThat(llm.getContainerProperties().getTopics()).containsExactly(LlmTopics.RESULTS);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) llm).getConcurrency()).isEqualTo(1);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) llm).getCommonErrorHandler())
                .as("발급 컨슈머의 재시도·DLT 핸들러와 다른 전용 핸들러")
                .isNotSameAs(((ConcurrentMessageListenerContainer<?, ?>) issue).getCommonErrorHandler());
    }
}

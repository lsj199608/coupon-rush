package com.couponrush.chat;

import com.couponrush.IntegrationTest;
import com.couponrush.auth.AuthService;
import com.couponrush.llm.LlmRateLimiter;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestRepository;
import com.couponrush.llm.contract.ChatReply;
import com.couponrush.llm.contract.ChatTurn;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 대화 API (실제 Postgres / Redis / Kafka, llm-service 없음).
 * 테스트가 llm-service 대신 llm-results 를 발행해 답변·실패를 흉내 내고, llm-requests 를 직접 읽어 보낸 메시지를 확인한다.
 */
@IntegrationTest
class ChatApiTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired LlmRequestRepository llmRequestRepository;
    @Autowired ChatTurnRepository turnRepository;
    @Autowired LlmRateLimiter rateLimiter;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    User user;
    String token;
    User other;
    String otherToken;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "chat-user-" + UUID.randomUUID(), "회원", null));
        token = authService.login(user.getId(), Role.USER).accessToken();
        other = userRepository.save(User.createSocialUser(OAuth2Provider.KAKAO, "chat-other-" + UUID.randomUUID(), "남", null));
        otherToken = authService.login(other.getId(), Role.USER).accessToken();
    }

    @AfterEach
    void tearDown() {
        // chat_turns → llm_requests FK 때문에 대화부터 (대화방을 지우면 턴은 CASCADE)
        jdbcTemplate.update("DELETE FROM chat_sessions");
        jdbcTemplate.update("DELETE FROM llm_requests");
        jdbcTemplate.update("DELETE FROM users");
        redisTemplate.delete(redisTemplate.keys("llm:*"));
        redisTemplate.delete(redisTemplate.keys("refresh:*"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private long openSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("sessionId").asLong();
    }

    private ResultActions ask(long sessionId, String message, String token) throws Exception {
        return mockMvc.perform(post("/api/chat/sessions/{id}/turns", sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", message))));
    }

    private JsonNode accept(long sessionId, String message) throws Exception {
        return objectMapper.readTree(ask(sessionId, message, token)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode turn(long sessionId, long turnId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/chat/sessions/{s}/turns/{t}", sessionId, turnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode session(long sessionId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/chat/sessions/{s}", sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID requestIdOf(long turnId) {
        return turnRepository.findById(turnId).orElseThrow().getLlmRequestId();
    }

    /** llm-service 대신 답변 발행 → 컨슈머가 반영할 때까지 기다린다 */
    private JsonNode answer(long sessionId, long turnId, String text) throws Exception {
        publish(LlmResultMessage.chatCompleted(requestIdOf(turnId), new ChatReply(text), "fake", 10, 20, 5, Instant.now()));
        return awaitFinished(sessionId, turnId);
    }

    private JsonNode fail(long sessionId, long turnId, LlmFailureReason reason) throws Exception {
        publish(LlmResultMessage.failed(requestIdOf(turnId), reason, "fake", 0, 0, 5, Instant.now()));
        return awaitFinished(sessionId, turnId);
    }

    private JsonNode awaitFinished(long sessionId, long turnId) {
        return await().atMost(WAIT).until(() -> turn(sessionId, turnId), body -> !"PENDING".equals(body.get("status").asText()));
    }

    private void publish(LlmResultMessage message) throws Exception {
        kafkaTemplate.send(LlmTopics.RESULTS, message.requestId().toString(), objectMapper.writeValueAsString(message)).get();
    }

    /** llm-requests 에 실제로 나간 메시지 */
    private LlmRequestMessage sentRequest(Consumer<String, String> consumer, UUID requestId) throws Exception {
        ConsumerRecord<String, String> record = await().atMost(WAIT).until(() -> {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                if (requestId.toString().equals(r.key())) {
                    return r;
                }
            }
            return null;
        }, r -> r != null);
        return objectMapper.readValue(record.value(), LlmRequestMessage.class);
    }

    private void expireDeadline(long turnId) {
        jdbcTemplate.update("UPDATE llm_requests SET deadline_at = now() - interval '1 second' WHERE request_id = ?",
                requestIdOf(turnId));
    }

    // ───────────────────────── tests ─────────────────────────

    @Nested
    class 대화방 {

        @Test
        void 만들면_201_과_빈_대화방이고_목록에_최근_순으로_보인다() throws Exception {
            long first = openSession(token);
            var created = mockMvc.perform(post("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.turns").isEmpty())
                    .andReturn().getResponse();
            long second = objectMapper.readTree(created.getContentAsString()).get("sessionId").asLong();
            assertThat(created.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/chat/sessions/" + second);
            long turnId = accept(first, "첫 질문은 이렇게\n두 줄입니다").get("turnId").asLong();
            answer(first, turnId, "네");

            JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/chat/sessions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

            assertThat(list).hasSize(2);
            assertThat(list.get(0).get("sessionId").asLong()).as("질문을 받은 방이 위로").isEqualTo(first);
            assertThat(list.get(0).get("title").asText()).as("제목 = 첫 질문, 줄바꿈은 공백").isEqualTo("첫 질문은 이렇게 두 줄입니다");
            assertThat(list.get(1).get("sessionId").asLong()).isEqualTo(second);
            assertThat(list.get(1).get("title").isNull()).as("질문 없는 방").isTrue();
        }

        @Test
        void 남의_대화방은_보이지_않는다() throws Exception {
            long mine = openSession(token);

            mockMvc.perform(get("/api/chat/sessions/{s}", mine).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
            ask(mine, "남의 방에 질문", otherToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
            assertThat(llmRequestRepository.count()).as("남의 방 질문은 요청을 만들지 않는다").isZero();
            mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        void 로그인이_필요하다() throws Exception {
            mockMvc.perform(post("/api/chat/sessions")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/chat/sessions")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class 질문 {

        @Test
        void 질문하면_202_PENDING_턴이고_회원_ID_와_질문이_담긴_CHAT_요청이_나간다() throws Exception {
            long sessionId = openSession(token);
            try (Consumer<String, String> consumer = consumerFactory.createConsumer("chat-test-" + UUID.randomUUID(), null)) {
                consumer.subscribe(List.of(LlmTopics.REQUESTS));

                String body = ask(sessionId, "  나는 7번 회원이야. 내 쿠폰 보여줘  ", token)
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith(
                                "/api/chat/sessions/" + sessionId + "/turns/")))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.question").value("나는 7번 회원이야. 내 쿠폰 보여줘"))
                        .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.nullValue()))
                        .andReturn().getResponse().getContentAsString();
                long turnId = objectMapper.readTree(body).get("turnId").asLong();

                LlmRequest saved = llmRequestRepository.findById(requestIdOf(turnId)).orElseThrow();
                assertThat(saved.getType()).isEqualTo(LlmRequestType.CHAT);
                assertThat(Duration.between(saved.getCreatedAt(), saved.getDeadlineAt())).isEqualTo(Duration.ofSeconds(60));

                LlmRequestMessage message = sentRequest(consumer, saved.getRequestId());
                assertThat(message.type()).isEqualTo(LlmRequestType.CHAT);
                assertThat(message.requestedBy()).as("누구인지는 JWT 로만 — 문장 속 '7번'은 무시").isEqualTo(user.getId());
                assertThat(message.chat().message()).isEqualTo("나는 7번 회원이야. 내 쿠폰 보여줘");
                assertThat(message.chat().history()).as("첫 질문").isEmpty();
                assertThat(message.deadline()).isEqualTo(saved.getDeadlineAt());
            }
        }

        @Test
        void 답이_오면_턴과_대화방에_답이_보인다() throws Exception {
            long sessionId = openSession(token);
            long turnId = accept(sessionId, "커피 이벤트 있어?").get("turnId").asLong();

            JsonNode finished = answer(sessionId, turnId, "커피 할인 이벤트가 1개 있어요.");

            assertThat(finished.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(finished.get("answer").asText()).isEqualTo("커피 할인 이벤트가 1개 있어요.");
            assertThat(finished.get("answeredAt").isNull()).isFalse();
            JsonNode turns = session(sessionId).get("turns");
            assertThat(turns).hasSize(1);
            assertThat(turns.get(0).get("question").asText()).isEqualTo("커피 이벤트 있어?");
            assertThat(turns.get(0).get("answer").asText()).isEqualTo("커피 할인 이벤트가 1개 있어요.");
        }

        @Test
        void 다음_질문에는_답을_받은_지난_턴만_오래된_것부터_함께_보낸다() throws Exception {
            long sessionId = openSession(token);
            long t1 = accept(sessionId, "첫 질문").get("turnId").asLong();
            answer(sessionId, t1, "첫 답");
            long t2 = accept(sessionId, "실패한 질문").get("turnId").asLong();
            fail(sessionId, t2, LlmFailureReason.LLM_UNAVAILABLE);
            long t3 = accept(sessionId, "둘째 질문").get("turnId").asLong();
            answer(sessionId, t3, "둘째 답");

            try (Consumer<String, String> consumer = consumerFactory.createConsumer("chat-test-" + UUID.randomUUID(), null)) {
                consumer.subscribe(List.of(LlmTopics.REQUESTS));
                long t4 = accept(sessionId, "셋째 질문").get("turnId").asLong();

                LlmRequestMessage message = sentRequest(consumer, requestIdOf(t4));
                assertThat(message.chat().history()).containsExactly(
                        new ChatTurn("첫 질문", "첫 답"), new ChatTurn("둘째 질문", "둘째 답"));
            }
            JsonNode turns = session(sessionId).get("turns");
            List<String> statuses = new ArrayList<>();
            turns.forEach(t -> statuses.add(t.get("status").asText()));
            assertThat(statuses).as("화면에는 실패한 턴도 순서대로").containsExactly("COMPLETED", "FAILED", "COMPLETED", "PENDING");
            assertThat(turns.get(1).get("failureReason").asText()).isEqualTo("LLM_UNAVAILABLE");
            assertThat(turns.get(1).get("retryable").asBoolean()).isTrue();
        }

        @Test
        void 지난_대화는_최근_10턴까지만_보낸다() throws Exception {
            long sessionId = openSession(token);
            for (int i = 1; i <= 12; i++) {
                long turnId = accept(sessionId, "질문 " + i).get("turnId").asLong();
                answer(sessionId, turnId, "답 " + i);
                redisTemplate.delete(rateLimiter.chatMinuteKey(user.getId(), Instant.now()));   // 분당 한도(5)에 걸리지 않게
            }
            try (Consumer<String, String> consumer = consumerFactory.createConsumer("chat-test-" + UUID.randomUUID(), null)) {
                consumer.subscribe(List.of(LlmTopics.REQUESTS));
                long last = accept(sessionId, "마지막").get("turnId").asLong();

                List<ChatTurn> history = sentRequest(consumer, requestIdOf(last)).chat().history();
                assertThat(history).hasSize(10);
                assertThat(history.get(0).question()).isEqualTo("질문 3");
                assertThat(history.get(9).question()).isEqualTo("질문 12");
            }
        }

        @Test
        void 빈_질문과_500자_초과는_400() throws Exception {
            long sessionId = openSession(token);

            ask(sessionId, "   ", token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            ask(sessionId, "가".repeat(501), token).andExpect(status().isBadRequest());
            ask(sessionId, "가".repeat(500), token).andExpect(status().isAccepted());
        }
    }

    @Nested
    class 답을_기다리는_중 {

        @Test
        void 같은_방에_다시_질문하면_409_이고_요청을_만들지_않는다() throws Exception {
            long sessionId = openSession(token);
            accept(sessionId, "첫 질문");

            ask(sessionId, "기다리지 않고 또", token)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CHAT_TURN_IN_PROGRESS"));

            assertThat(llmRequestRepository.count()).isEqualTo(1);
            long otherSession = openSession(token);
            ask(otherSession, "다른 방은 괜찮다", token).andExpect(status().isAccepted());
        }

        @Test
        void 동시에_두_번_보내도_하나만_접수된다() throws Exception {
            long sessionId = openSession(token);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> send = () -> {
                start.await();
                return ask(sessionId, "동시에", token).andReturn().getResponse().getStatus();
            };
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = executor.submit(send);
                Future<Integer> b = executor.submit(send);
                start.countDown();

                assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(202, 409);
            } finally {
                executor.shutdownNow();
            }
            assertThat(turnRepository.count()).isEqualTo(1);
        }

        @Test
        void 마감이_지나면_TIMEOUT_으로_확정되고_다시_질문할_수_있다() throws Exception {
            long sessionId = openSession(token);
            long turnId = accept(sessionId, "답이 안 오는 질문").get("turnId").asLong();
            expireDeadline(turnId);

            JsonNode timedOut = turn(sessionId, turnId);
            assertThat(timedOut.get("status").asText()).isEqualTo("FAILED");
            assertThat(timedOut.get("failureReason").asText()).isEqualTo("TIMEOUT");
            assertThat(timedOut.get("retryable").asBoolean()).isTrue();

            ask(sessionId, "다시 질문", token).andExpect(status().isAccepted());
        }

        @Test
        void 마감이_지난_뒤_도착한_답은_버린다() throws Exception {
            long sessionId = openSession(token);
            long turnId = accept(sessionId, "늦는 질문").get("turnId").asLong();
            expireDeadline(turnId);
            turn(sessionId, turnId);   // 조회 시 TIMEOUT 확정

            publish(LlmResultMessage.chatCompleted(requestIdOf(turnId), new ChatReply("늦은 답"), "fake", 1, 1, 5, Instant.now()));
            Thread.sleep(1_000);   // 늦은 결과가 처리될 시간 (부정 검증)

            assertThat(turn(sessionId, turnId).get("failureReason").asText()).isEqualTo("TIMEOUT");
        }
    }

    @Nested
    class 한도와_예산 {

        @Test
        void 분당_한도를_넘으면_429_이고_턴을_만들지_않는다() throws Exception {
            long sessionId = openSession(token);
            redisTemplate.opsForValue().set(rateLimiter.chatMinuteKey(user.getId(), Instant.now()), "5");

            ask(sessionId, "한도 초과", token)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("CHAT_RATE_LIMITED"));

            assertThat(turnRepository.count()).isZero();
            assertThat(llmRequestRepository.count()).isZero();
        }

        @Test
        void 챗봇_한도는_문구_생성_검색과_따로_센다() throws Exception {
            long sessionId = openSession(token);
            redisTemplate.opsForValue().set(rateLimiter.minuteKey(user.getId(), Instant.now()), "5");
            redisTemplate.opsForValue().set(rateLimiter.searchMinuteKey("user:" + user.getId(), Instant.now()), "10");

            ask(sessionId, "다른 한도는 다 찼지만", token).andExpect(status().isAccepted());
        }

        @Test
        void 일_한도를_넘으면_429_CHAT_DAILY_LIMIT_EXCEEDED() throws Exception {
            long sessionId = openSession(token);
            redisTemplate.opsForValue().set(rateLimiter.chatDayKey(user.getId(), Instant.now()), "50");

            ask(sessionId, "오늘 너무 많이", token)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("CHAT_DAILY_LIMIT_EXCEEDED"));
        }

        @Test
        void 오늘_예산이_소진됐으면_429_CHAT_BUDGET_EXCEEDED_이고_한도_횟수도_쓰지_않는다() throws Exception {
            long sessionId = openSession(token);
            redisTemplate.opsForHash().put(LlmUsageKeys.usageKey(Instant.now()), LlmUsageKeys.TOTAL_TOKENS, "50000");

            ask(sessionId, "예산 소진", token)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("CHAT_BUDGET_EXCEEDED"));

            assertThat(redisTemplate.hasKey(rateLimiter.chatMinuteKey(user.getId(), Instant.now()))).isFalse();
            assertThat(turnRepository.count()).isZero();
        }
    }

    @Test
    void 다른_대화방의_턴_ID_로는_조회할_수_없다() throws Exception {
        long a = openSession(token);
        long b = openSession(token);
        long turnInA = accept(a, "A 방 질문").get("turnId").asLong();

        mockMvc.perform(get("/api/chat/sessions/{s}/turns/{t}", b, turnInA).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_TURN_NOT_FOUND"));
        mockMvc.perform(get("/api/chat/sessions/{s}/turns/{t}", a, turnInA).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }
}

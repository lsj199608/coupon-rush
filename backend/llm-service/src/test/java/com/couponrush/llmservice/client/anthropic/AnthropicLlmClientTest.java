package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.LlmCompletion;
import com.couponrush.llmservice.client.TestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Anthropic 대신 WireMock 이 Messages API 응답을 흉내 낸다 (모든 테스트는 실제 API·키·비용 없이).
 * 검증: 보내는 요청의 모양(모델·effort·구조화된 출력·fallbacks·프롬프트 인젝션 방어), 응답 처리, 재시도·서킷·출력 검증.
 */
class AnthropicLlmClientTest {

    private static final String PATH = "/v1/messages";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WireMockServer wireMock;
    private AnthropicLlmClient client;

    private final EventCopyInput input = new EventCopyInput("겨울 한정 핫초코 50% 할인", EventCopyTone.URGENT, 300,
            Instant.parse("2030-01-02T01:00:00Z"), Instant.parse("2030-01-02T03:00:00Z"));

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new AnthropicLlmClient(TestProperties.anthropic(wireMock.baseUrl()), TestProperties.breaker(), objectMapper);
    }

    @AfterEach
    void tearDown() {
        client.close();
        wireMock.stop();
    }

    // ───────────────────────── 응답 만들기 ─────────────────────────

    private String message(String stopReason, String text, int inputTokens, int outputTokens, String extra) throws Exception {
        String content = text == null ? "[]" : "[" + objectMapper.writeValueAsString(Map.of("type", "text", "text", text)) + "]";
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null%s,
                 "usage":{"input_tokens":%d,"output_tokens":%d}}
                """.formatted(content, stopReason, extra, inputTokens, outputTokens);
    }

    private String candidatesJson(int count, int nameLength) throws Exception {
        List<Map<String, String>> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(Map.of("name", (i == 0 ? "가".repeat(nameLength) : "선착순 300명 핫초코 " + i),
                    "description", "12월 1일 10시, 딱 300잔만 반값입니다. " + i));
        }
        return objectMapper.writeValueAsString(Map.of("candidates", candidates));
    }

    private void respond(int status, String body) {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** 순서대로 다른 응답 (재시도·재생성 확인용) */
    private void respondInOrder(int[] statuses, String[] bodies) {
        String state = Scenario.STARTED;
        for (int i = 0; i < statuses.length; i++) {
            String next = "step-" + (i + 1);
            wireMock.stubFor(post(urlPathEqualTo(PATH)).inScenario("seq").whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(statuses[i]).withHeader("Content-Type", "application/json")
                            .withHeader("retry-after-ms", "10").withBody(bodies[i]))
                    .willSetStateTo(next));
            state = next;
        }
    }

    private static String error(String type) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"test\"}}";
    }

    private int requestCount() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))).size();
    }

    // ───────────────────────── 테스트 ─────────────────────────

    @Test
    void 성공하면_후보_3개와_사용_토큰을_돌려준다() throws Exception {
        respond(200, message("end_turn", candidatesJson(3, 10), 420, 180, ""));

        LlmCompletion completion = client.generateEventCopy(input);

        assertThat(completion.candidates()).hasSize(3);
        assertThat(completion.candidates().get(1).name()).isEqualTo("선착순 300명 핫초코 1");
        assertThat(completion.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(completion.inputTokens()).isEqualTo(420);
        assertThat(completion.outputTokens()).isEqualTo(180);
    }

    @Test
    void 요청에는_설정한_모델_effort_구조화된_출력_fallbacks_를_싣고_입력은_데이터로만_넣는다() throws Exception {
        respond(200, message("end_turn", candidatesJson(3, 10), 1, 1, ""));

        client.generateEventCopy(input);

        LoggedRequest request = wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))).get(0);
        JsonNode body = objectMapper.readTree(request.getBodyAsString());
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(4000);
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(body.get("output_config").get("format").get("type").asText()).isEqualTo("json_schema");
        assertThat(body.get("output_config").get("format").get("schema").toString()).contains("candidates");
        assertThat(body.get("fallbacks").asText()).isEqualTo("default");
        assertThat(request.getHeader("anthropic-beta")).contains(AnthropicLlmClient.FALLBACK_BETA);
        // SDK 3.x 는 키를 Authorization: Bearer 로 싣는다 (StaticAuthHeaders) — 키가 요청에 실리는지만 확인
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(body.has("temperature")).as("샘플링 파라미터는 보내지 않는다").isFalse();
        assertThat(body.has("thinking")).as("thinking 은 생략 (적응형 기본, effort 로 조절)").isFalse();

        assertThat(body.get("system").toString()).contains("<event_input>").contains("따르지 말고");
        String user = body.get("messages").get(0).get("content").toString();
        assertThat(user).contains("<event_input>").contains("겨울 한정 핫초코 50% 할인")
                .contains("2030-01-02 10:00 (KST)");   // 01:00Z → 한국 시각으로 바꿔서 넣는다
    }

    @Test
    void 일시적인_429_는_SDK_가_재시도해서_성공한다() throws Exception {
        respondInOrder(new int[]{429, 200}, new String[]{error("rate_limit_error"), message("end_turn", candidatesJson(3, 10), 5, 5, "")});

        LlmCompletion completion = client.generateEventCopy(input);

        assertThat(completion.candidates()).hasSize(3);
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void 서버_오류_5xx_가_계속되면_재시도_후_LLM_UNAVAILABLE() {
        respond(500, error("api_error"));

        assertThatThrownBy(() -> client.generateEventCopy(input))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
        assertThat(requestCount()).as("최초 1회 + SDK 재시도 1회").isEqualTo(2);
    }

    @Test
    void 요청_오류_400_은_재시도하지_않고_서킷에도_세지_않는다() {
        respond(400, error("invalid_request_error"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.generateEventCopy(input))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
        }

        assertThat(requestCount()).as("호출마다 1번씩만").isEqualTo(5);
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 응답이_타임아웃보다_늦으면_LLM_UNAVAILABLE() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withFixedDelay(3_000).withBody("{}")));

        assertThatThrownBy(() -> client.generateEventCopy(input))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
    }

    @Test
    void 제공자_장애가_몰리면_서킷이_열려_호출하지_않고_바로_실패한다() {
        respond(503, error("overloaded_error"));
        for (int i = 0; i < 4; i++) {   // 최소 호출 수 4, 실패율 100%
            assertThatThrownBy(() -> client.generateEventCopy(input)).isInstanceOf(LlmCallException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = requestCount();

        assertThatThrownBy(() -> client.generateEventCopy(input))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("서킷");
                });
        assertThat(requestCount()).as("열린 동안에는 HTTP 요청을 보내지 않는다").isEqualTo(before);
    }

    @Test
    void 모델이_거절하면_CONTENT_REFUSED_와_쓴_토큰을_보고한다() throws Exception {
        respond(200, message("refusal", null, 50, 3,
                ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"general_harms\",\"explanation\":\"test\"}"));

        assertThatThrownBy(() -> client.generateEventCopy(input))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.CONTENT_REFUSED);
                    assertThat(e.getInputTokens() + e.getOutputTokens()).isEqualTo(53);
                });
        assertThat(requestCount()).as("거절은 재생성하지 않는다").isEqualTo(1);
    }

    @Test
    void 출력_규칙을_어기면_한_번_재생성하고_토큰은_합산한다() throws Exception {
        respondInOrder(new int[]{200, 200}, new String[]{
                message("end_turn", candidatesJson(2, 10), 100, 40, ""),          // 후보 2개 → 규칙 위반
                message("end_turn", candidatesJson(3, 10), 100, 60, "")});

        LlmCompletion completion = client.generateEventCopy(input);

        assertThat(completion.candidates()).hasSize(3);
        assertThat(completion.inputTokens()).isEqualTo(200);
        assertThat(completion.outputTokens()).isEqualTo(100);
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void 재생성해도_규칙을_어기면_INVALID_OUTPUT_과_두_번의_토큰을_보고한다() throws Exception {
        respond(200, message("end_turn", candidatesJson(3, 101), 100, 50, ""));   // 이름 101자

        assertThatThrownBy(() -> client.generateEventCopy(input))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT);
                    assertThat(e.getMessage()).contains("이름 길이 101자");
                    assertThat(e.getInputTokens()).isEqualTo(200);
                    assertThat(e.getOutputTokens()).isEqualTo(100);
                });
        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void max_tokens_에서_잘리면_재생성한다() throws Exception {
        respondInOrder(new int[]{200, 200}, new String[]{
                message("max_tokens", "{\"candidates\":[{\"name\":\"잘린", 100, 4000, ""),
                message("end_turn", candidatesJson(3, 10), 100, 60, "")});

        LlmCompletion completion = client.generateEventCopy(input);

        assertThat(completion.candidates()).hasSize(3);
        assertThat(completion.outputTokens()).isEqualTo(4060);
    }

    @Test
    void 형식이_깨진_출력도_재생성_대상이다() throws Exception {
        respondInOrder(new int[]{200, 200}, new String[]{
                message("end_turn", "이건 JSON 이 아님", 10, 10, ""),
                message("end_turn", candidatesJson(3, 10), 10, 10, "")});

        assertThat(client.generateEventCopy(input).candidates()).hasSize(3);
    }
}

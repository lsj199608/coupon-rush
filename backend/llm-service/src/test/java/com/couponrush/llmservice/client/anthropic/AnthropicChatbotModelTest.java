package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llm.contract.ChatbotTurn;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.chatbot.model.ChatbotConversation;
import com.couponrush.llmservice.chatbot.model.ModelTurn;
import com.couponrush.llmservice.chatbot.tool.ToolCall;
import com.couponrush.llmservice.chatbot.tool.ToolOutcome;
import com.couponrush.llmservice.chatbot.tool.ToolSpec;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.TestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Claude tool use 왕복을 WireMock 으로 (실제 API·비용 없음): 도구 정의, tool_use → tool_result, 도구 끄기, 거절 */
class AnthropicChatbotModelTest {

    private static final String PATH = "/v1/messages";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WireMockServer wireMock;
    private AnthropicLlmClient client;
    private AnthropicChatbotModel model;

    private static final ToolSpec SEARCH = new ToolSpec("search_events", "이벤트 검색", Map.of(
            "type", "object",
            "properties", Map.of("query", Map.of("type", "string", "maxLength", 200)),
            "required", List.of("query"),
            "additionalProperties", false));

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new AnthropicLlmClient(TestProperties.anthropic(wireMock.baseUrl()), TestProperties.breaker(), objectMapper);
        model = new AnthropicChatbotModel(client, TestProperties.chatbot("http://unused/mcp", "t"));
    }

    @AfterEach
    void tearDown() {
        client.close();
        wireMock.stop();
    }

    private static String message(String stopReason, String content, int in, int out) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":%d}}""".formatted(content, stopReason, in, out);
    }

    private static final String TOOL_USE = """
            [{"type":"thinking","thinking":"","signature":"sig-1"},
             {"type":"tool_use","id":"toolu_1","name":"search_events","input":{"query":"커피 쿠폰"}}]""";
    private static final String TEXT = "[{\"type\":\"text\",\"text\":\"커피 이벤트가 1개 있어요.\"}]";

    private void respondInOrder(String... bodies) {
        String state = Scenario.STARTED;
        for (int i = 0; i < bodies.length; i++) {
            String next = "step-" + (i + 1);
            wireMock.stubFor(post(urlPathEqualTo(PATH)).inScenario("seq").whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(bodies[i]))
                    .willSetStateTo(next));
            state = next;
        }
    }

    private JsonNode request(int index) throws Exception {
        return objectMapper.readTree(wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))).get(index).getBodyAsString());
    }

    private ChatbotConversation start() {
        return model.start(new ChatbotInput("커피 쿠폰 있어?", List.of(new ChatbotTurn("안녕", "안녕하세요!"))),
                List.of(SEARCH), LocalDate.of(2030, 1, 7));
    }

    @Test
    void 도구_호출을_돌려주고_결과를_붙여_다시_부르면_답을_돌려준다() throws Exception {
        respondInOrder(message("tool_use", TOOL_USE, 300, 40), message("end_turn", TEXT, 400, 20));
        ChatbotConversation conversation = start();

        ModelTurn first = conversation.next(true);
        assertThat(first).isInstanceOf(ModelTurn.ToolRequests.class);
        ToolCall call = ((ModelTurn.ToolRequests) first).calls().get(0);
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.arguments()).containsEntry("query", "커피 쿠폰");
        assertThat(first.inputTokens()).isEqualTo(300);

        conversation.addToolResults(List.of(new ToolOutcome("toolu_1", "{\"count\":1}", false)));
        ModelTurn second = conversation.next(true);
        assertThat(((ModelTurn.Answer) second).text()).isEqualTo("커피 이벤트가 1개 있어요.");

        JsonNode firstRequest = request(0);
        assertThat(firstRequest.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(firstRequest.get("system").asText()).contains("발급").contains("오늘은 2030-01-07 (월요일)");
        assertThat(firstRequest.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(firstRequest.has("thinking")).as("적응형 기본 — thinking 은 보내지 않음").isFalse();
        assertThat(firstRequest.get("fallbacks").asText()).isEqualTo("default");
        JsonNode tool = firstRequest.get("tools").get(0);
        assertThat(tool.get("name").asText()).isEqualTo("search_events");
        assertThat(tool.get("input_schema").get("properties").get("query").get("maxLength").asInt()).isEqualTo(200);
        assertThat(tool.get("input_schema").get("required").get(0).asText()).isEqualTo("query");
        assertThat(tool.get("input_schema").get("additionalProperties").asBoolean()).isFalse();
        assertThat(firstRequest.get("tool_choice").get("type").asText()).isEqualTo("auto");
        JsonNode firstMessages = firstRequest.get("messages");
        assertThat(firstMessages).as("지난 턴 2개 + 이번 질문").hasSize(3);

        JsonNode messages = request(1).get("messages");
        assertThat(messages).hasSize(5);
        JsonNode assistant = messages.get(3);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").get(0).get("type").asText()).as("생각 블록을 그대로 이어 붙인다").isEqualTo("thinking");
        assertThat(assistant.get("content").get(0).get("signature").asText()).isEqualTo("sig-1");
        assertThat(assistant.get("content").get(1).get("type").asText()).isEqualTo("tool_use");
        JsonNode result = messages.get(4).get("content").get(0);
        assertThat(result.get("type").asText()).isEqualTo("tool_result");
        assertThat(result.get("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(result.get("is_error").asBoolean()).isFalse();
    }

    @Test
    void 도구를_끄면_tool_choice_none_으로_보낸다_도구_정의는_남긴다() throws Exception {
        respondInOrder(message("end_turn", TEXT, 10, 5));

        start().next(false);

        JsonNode body = request(0);
        assertThat(body.get("tool_choice").get("type").asText()).isEqualTo("none");
        assertThat(body.get("tools")).hasSize(1);
    }

    @Test
    void 거절은_CONTENT_REFUSED_잘림은_INVALID_OUTPUT_이고_토큰을_담는다() {
        respondInOrder(message("refusal", "[]", 50, 3));
        assertThatThrownBy(() -> start().next(true)).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(LlmFailureReason.CONTENT_REFUSED);
            assertThat(e.getInputTokens()).isEqualTo(50);
        });

        wireMock.resetAll();
        respondInOrder(message("max_tokens", TEXT, 60, 8000));
        assertThatThrownBy(() -> start().next(true)).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT);
            assertThat(e.getOutputTokens()).isEqualTo(8000);
        });
    }

    @Test
    void 제공자_장애는_LLM_UNAVAILABLE() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}")));

        assertThatThrownBy(() -> start().next(true)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
    }
}

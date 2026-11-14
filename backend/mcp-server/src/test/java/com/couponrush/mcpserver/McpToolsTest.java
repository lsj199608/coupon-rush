package com.couponrush.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MCP 클라이언트(공식 SDK, Streamable HTTP)로 서버에 붙어 도구 목록·호출을 검증한다. coupon-api 는 WireMock.
 * 포트는 고정(18082) — Host 헤더 허용 목록(DNS 리바인딩 방지)을 기동 전에 정해야 해서 임의 포트를 쓸 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18082",
        "app.mcp.allowed-hosts=localhost:18082",
        "app.mcp.search.wait-timeout=1s",
        "app.mcp.search.poll-interval=50ms"
})
class McpToolsTest {

    static final WireMockServer couponApi = new WireMockServer(options().dynamicPort());

    static {
        couponApi.start();
    }

    @DynamicPropertySource
    static void couponApiUrl(DynamicPropertyRegistry registry) {
        registry.add("app.mcp.coupon-api.base-url", couponApi::baseUrl);
    }

    @AfterAll
    static void stopCouponApi() {
        couponApi.stop();
    }

    final ObjectMapper objectMapper = new ObjectMapper();
    McpSyncClient client;
    InitializeResult initialized;

    @BeforeEach
    void connect() {
        couponApi.resetAll();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:18082")
                        .endpoint("/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        initialized = client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    // ───────────────────────── helpers ─────────────────────────

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private JsonNode json(CallToolResult result) throws Exception {
        assertThat(result.isError()).as(text(result)).isFalse();
        return objectMapper.readTree(text(result));
    }

    private static String event(long id, String name, String status) {
        return """
                {"id":%d,"name":"%s","description":null,"totalQuantity":100,"issuedQuantity":30,"remainingQuantity":70,
                 "issueStartAt":"2026-11-14T01:00:00Z","issueEndAt":"2026-11-15T01:00:00Z","status":"%s",
                 "serverTime":"2026-11-14T02:00:00Z"}""".formatted(id, name, status);
    }

    /** 오류는 도구 결과(isError)로 오거나, 입력 스키마 위반이면 SDK 가 프로토콜 오류로 거절할 수 있다 — 둘 다 "거절" */
    private static void assertRejected(Supplier<CallToolResult> call) {
        try {
            CallToolResult result = call.get();
            assertThat(result.isError()).as("잘못된 인자는 거절돼야 한다").isTrue();
        } catch (McpError expected) {
            // SDK 의 입력 검증
        }
    }

    // ───────────────────────── 연결·목록 ─────────────────────────

    @Test
    void 서버_정보와_안내_조회_전용_도구_2개를_알려_준다() {
        assertThat(initialized.serverInfo().name()).isEqualTo("coupon-rush");
        assertThat(initialized.instructions()).contains("조회 전용").contains("발급");

        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder("search_events", "get_event");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
            assertThat(tool.description()).isNotBlank();
        });
        assertThat(tools).filteredOn(tool -> tool.name().equals("search_events"))
                .singleElement().satisfies(tool -> assertThat(tool.inputSchema().get("required")).isEqualTo(List.of("query")));
    }

    // ───────────────────────── search_events ─────────────────────────

    @Nested
    class search_events {

        @Test
        void AI_조건_생성을_기다렸다가_조건과_이벤트를_한_번에_돌려준다() throws Exception {
            couponApi.stubFor(post("/api/coupons/search").willReturn(aResponse().withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {"requestId":"s-1","status":"PENDING","query":"진행 중인 커피","events":[],"keywords":[]}""")));
            couponApi.stubFor(get("/api/coupons/search/s-1").inScenario("poll").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(okJson("""
                            {"requestId":"s-1","status":"PENDING","query":"진행 중인 커피","events":[],"keywords":[]}"""))
                    .willSetStateTo("done"));
            couponApi.stubFor(get("/api/coupons/search/s-1").inScenario("poll").whenScenarioStateIs("done")
                    .willReturn(okJson("""
                            {"requestId":"s-1","status":"COMPLETED","query":"진행 중인 커피",
                             "filter":{"keywords":["커피"],"statuses":["OPEN"],"startFrom":null,"startTo":null,
                                       "endFrom":null,"endTo":null,"availableOnly":false},
                             "keywords":[],"events":[%s]}""".formatted(event(1, "커피 할인", "OPEN")))));

            JsonNode body = json(call("search_events", Map.of("query", "  진행 중인   커피 ")));

            couponApi.verify(postRequestedFor(urlEqualTo("/api/coupons/search"))
                    .withRequestBody(equalToJson("{\"query\":\"진행 중인 커피\"}")));
            assertThat(body.get("searchMethod").asText()).isEqualTo("AI_FILTER");
            assertThat(body.get("conditions").get("keywords").get(0).asText()).isEqualTo("커피");
            assertThat(body.get("count").asInt()).isEqualTo(1);
            JsonNode event = body.get("events").get(0);
            assertThat(event.get("name").asText()).isEqualTo("커피 할인");
            assertThat(event.get("statusLabel").asText()).isEqualTo("진행 중");
            assertThat(event.get("remainingQuantity").asInt()).isEqualTo(70);
            assertThat(event.get("issueStart").asText()).isEqualTo("2026-11-14 10:00 KST");
            assertThat(event.get("url").asText()).isEqualTo("http://localhost:5173/events/1");
        }

        @Test
        void coupon_api_가_키워드_검색으로_대신하면_그_사실과_이유를_알려_준다() throws Exception {
            couponApi.stubFor(post("/api/coupons/search").willReturn(okJson("""
                    {"requestId":null,"status":"FALLBACK","query":"치킨","fallbackReason":"RATE_LIMITED",
                     "keywords":["치킨"],"events":[%s]}""".formatted(event(2, "치킨 반값", "OPEN")))));

            JsonNode body = json(call("search_events", Map.of("query", "치킨")));

            assertThat(body.get("searchMethod").asText()).isEqualTo("KEYWORD");
            assertThat(body.get("fallbackReason").asText()).isEqualTo("RATE_LIMITED");
            assertThat(body.get("keywords").get(0).asText()).isEqualTo("치킨");
            assertThat(body.get("conditions").isNull()).isTrue();
            assertThat(body.get("events").get(0).get("id").asLong()).isEqualTo(2);
        }

        @Test
        void 결과가_대기_한도_안에_오지_않으면_도구_오류() {
            couponApi.stubFor(post("/api/coupons/search").willReturn(aResponse().withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"requestId\":\"s-2\",\"status\":\"PENDING\",\"query\":\"커피\"}")));
            couponApi.stubFor(get("/api/coupons/search/s-2")
                    .willReturn(okJson("{\"requestId\":\"s-2\",\"status\":\"PENDING\",\"query\":\"커피\"}")));

            CallToolResult result = call("search_events", Map.of("query", "커피"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("늦어지고");
        }

        @Test
        void 질문이_비었으면_coupon_api_를_부르지_않고_거절() {
            assertRejected(() -> call("search_events", Map.of("query", "   ")));
            assertRejected(() -> call("search_events", Map.of()));
            couponApi.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    // ───────────────────────── get_event ─────────────────────────

    @Nested
    class get_event {

        @Test
        void 이벤트_상세와_페이지_링크() throws Exception {
            couponApi.stubFor(get("/api/coupons/7").willReturn(okJson(event(7, "오픈 예정 쿠폰", "UPCOMING"))));

            JsonNode body = json(call("get_event", Map.of("eventId", 7)));

            assertThat(body.get("id").asLong()).isEqualTo(7);
            assertThat(body.get("statusLabel").asText()).isEqualTo("오픈 전");
            assertThat(body.get("issueEnd").asText()).isEqualTo("2026-11-15 10:00 KST");
            assertThat(body.get("url").asText()).isEqualTo("http://localhost:5173/events/7");
        }

        @Test
        void 없는_이벤트는_coupon_api_메시지와_함께_도구_오류() {
            couponApi.stubFor(get("/api/coupons/404").willReturn(aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"COUPON_NOT_FOUND\",\"message\":\"쿠폰을 찾을 수 없습니다.\"}")));

            CallToolResult result = call("get_event", Map.of("eventId", 404));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("찾을 수 없습니다: 쿠폰을 찾을 수 없습니다.");
        }

        @Test
        void 잘못된_ID_는_coupon_api_를_부르지_않고_거절() {
            assertRejected(() -> call("get_event", Map.of("eventId", 0)));
            assertRejected(() -> call("get_event", Map.of("eventId", "abc")));
            couponApi.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    // ───────────────────────── coupon-api 장애 ─────────────────────────

    @Nested
    class coupon_api_장애 {

        @Test
        void 서버_오류면_잠시_후_다시_하라는_도구_오류() {
            couponApi.stubFor(get("/api/coupons/1").willReturn(aResponse().withStatus(500)));

            CallToolResult result = call("get_event", Map.of("eventId", 1));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("응답하지 않습니다");
        }

        @Test
        void 연결이_끊기면_연결할_수_없다는_도구_오류_서버는_계속_동작한다() {
            couponApi.stubFor(get("/api/coupons/1").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            CallToolResult result = call("get_event", Map.of("eventId", 1));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("연결할 수 없습니다");
            assertThat(client.listTools().tools()).as("도구 오류 뒤에도 같은 세션으로 계속 쓸 수 있다").hasSize(2);
        }
    }
}

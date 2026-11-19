package com.couponrush.llmservice.chat.tool;

import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.ServletRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MCP 프로토콜로 붙는지 (공식 SDK 서버를 내장 Tomcat 에 띄움 — mcp-server 모듈에 의존하지 않고).
 * 확인: 연결 헤더(서비스 토큰·회원 ID)가 매 요청에 실리는지, 도구 목록·호출 결과·오류, 토큰이 틀리거나 비었을 때.
 */
class McpToolGatewayTest {

    private static final String TOKEN = "test-service-token";

    private WebServer server;
    private McpSyncServer mcp;
    private String url;

    @BeforeEach
    void startServer() {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .httpHeaderValidator(headers -> {
                    var sent = headers.getHeader("X-Mcp-Service-Token");
                    if (sent == null || !sent.contains(TOKEN)) {
                        throw new ServerTransportSecurityException(401, "Invalid service token");
                    }
                })
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        "user", String.valueOf(request.getHeader("X-Mcp-User-Id")))))
                .build();
        Tool whoami = Tool.builder("whoami", jsonMapper, """
                {"type":"object","additionalProperties":false,"properties":{}}""").description("누구로 연결됐는지").build();
        Tool fail = Tool.builder("fail", jsonMapper, """
                {"type":"object","properties":{"reason":{"type":"string"}},"required":["reason"]}""").build();
        mcp = McpServer.sync(transport)
                .serverInfo("test", "1")
                .jsonMapper(jsonMapper)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(SyncToolSpecification.builder().tool(whoami).callHandler((exchange, request) ->
                                CallToolResult.builder().addTextContent("user=" + exchange.transportContext().get("user"))
                                        .isError(false).build()).build(),
                        SyncToolSpecification.builder().tool(fail).callHandler((exchange, request) ->
                                CallToolResult.builder().addTextContent("실패: " + request.arguments().get("reason"))
                                        .isError(true).build()).build())
                .build();

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        server = factory.getWebServer(context -> {
            ServletRegistration.Dynamic registration = context.addServlet("mcp", transport);
            registration.addMapping("/mcp");
            registration.setAsyncSupported(true);
        });
        server.start();
        url = "http://localhost:" + server.getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        mcp.close();
        server.stop();
    }

    private McpToolGateway gateway(String token) {
        return new McpToolGateway(TestProperties.with(TestProperties.anthropic("http://unused"), TestProperties.chat(url, token)));
    }

    @Test
    void 회원_ID_헤더로_연결해_도구_목록을_받고_호출한다() {
        try (ToolSession session = gateway(TOKEN).open(42L)) {
            assertThat(session.tools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("whoami", "fail");
            ToolSpec whoami = session.tools().stream().filter(t -> t.name().equals("whoami")).findFirst().orElseThrow();
            assertThat(whoami.description()).isEqualTo("누구로 연결됐는지");
            assertThat(whoami.inputSchema()).containsEntry("additionalProperties", false);

            ToolOutcome outcome = session.call(new ToolCall("c1", "whoami", Map.of()));

            assertThat(outcome.callId()).isEqualTo("c1");
            assertThat(outcome.text()).as("도구 호출(연결 이후 요청)에도 회원 헤더가 실린다").isEqualTo("user=42");
            assertThat(outcome.isError()).isFalse();
        }
    }

    @Test
    void 도구가_실패하면_isError_결과로_돌려준다_예외가_아니다() {
        try (ToolSession session = gateway(TOKEN).open(42L)) {
            ToolOutcome failed = session.call(new ToolCall("c1", "fail", Map.of("reason", "테스트")));
            assertThat(failed.isError()).isTrue();
            assertThat(failed.text()).isEqualTo("실패: 테스트");

            ToolOutcome unknown = session.call(new ToolCall("c2", "no_such_tool", Map.of()));
            assertThat(unknown.isError()).isTrue();
            assertThat(unknown.callId()).isEqualTo("c2");
        }
    }

    @Test
    void 서비스_토큰이_틀리거나_비면_LLM_UNAVAILABLE() {
        assertThatThrownBy(() -> gateway("wrong").open(42L)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
        assertThatThrownBy(() -> gateway("").open(42L)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.getMessage()).contains("설정"));
    }

    @Test
    void 서버가_없으면_LLM_UNAVAILABLE() {
        McpToolGateway nowhere = new McpToolGateway(TestProperties.with(TestProperties.anthropic("http://unused"),
                TestProperties.chat("http://localhost:1/mcp", TOKEN)));

        assertThatThrownBy(() -> nowhere.open(42L)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE));
    }
}

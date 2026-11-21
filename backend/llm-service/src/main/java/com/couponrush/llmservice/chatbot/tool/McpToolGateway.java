package com.couponrush.llmservice.chatbot.tool;

import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.client.LlmCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * mcp-server 에 붙는 MCP 클라이언트 (공식 Java SDK, Streamable HTTP). 질문마다 연결(initialize)을 새로 연다 —
 * 연결 헤더에 회원(X-Mcp-User-Id)이 실리므로 질문(=회원)마다 연결을 나눠야 한다. 한 번에 수십 ms 라 질문당 수 초인 챗봇에서는 무시할 만하다.
 * <p>
 * 헤더: X-Mcp-Service-Token(MCP_SERVICE_TOKEN) + X-Mcp-User-Id(질문한 회원). mcp-server 는 토큰이 틀리면 연결부터 401.
 * 도구 목록은 연결할 때 tools/list 로 받는다 — 도구가 바뀌어도 이 서비스를 고치지 않는다.
 */
@Component
public class McpToolGateway implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(McpToolGateway.class);

    static final String SERVICE_TOKEN_HEADER = "X-Mcp-Service-Token";
    static final String USER_ID_HEADER = "X-Mcp-User-Id";

    private final LlmServiceProperties.Chatbot.Mcp config;
    private final JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

    public McpToolGateway(LlmServiceProperties properties) {
        this.config = properties.chatbot().mcp();
        if (config.serviceToken() == null || config.serviceToken().isBlank()) {
            log.warn("app.llm.chatbot.mcp.service-token(MCP_SERVICE_TOKEN)이 비어 있어 챗봇이 도구를 쓸 수 없습니다 (질문은 LLM_UNAVAILABLE)");
        }
    }

    @Override
    public ToolSession open(long userId) {
        if (config.serviceToken() == null || config.serviceToken().isBlank()) {
            log.error("챗봇 도구 연결 불가: MCP_SERVICE_TOKEN 이 비어 있음");
            throw new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "도구 서버 연결이 설정되지 않았습니다");
        }
        URI uri = URI.create(config.url());
        String base = uri.getScheme() + "://" + uri.getAuthority();
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base)
                        .endpoint(uri.getPath())
                        .jsonMapper(jsonMapper)
                        .httpRequestCustomizer((builder, method, requestUri, body, context) -> builder
                                .header(SERVICE_TOKEN_HEADER, config.serviceToken())
                                .header(USER_ID_HEADER, Long.toString(userId)))
                        .build())
                .requestTimeout(config.requestTimeout())
                .build();
        try {
            client.initialize();
            List<ToolSpec> tools = client.listTools().tools().stream()
                    .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.inputSchema()))
                    .toList();
            return new Session(client, tools);
        } catch (RuntimeException e) {
            client.close();
            log.warn("도구 서버(mcp-server) 연결 실패: url={}", config.url(), e);
            throw new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "도구 서버에 연결하지 못했습니다", null, 0, 0, e);
        }
    }

    private static final class Session implements ToolSession {

        private final McpSyncClient client;
        private final List<ToolSpec> tools;

        Session(McpSyncClient client, List<ToolSpec> tools) {
            this.client = client;
            this.tools = tools;
        }

        @Override
        public List<ToolSpec> tools() {
            return tools;
        }

        @Override
        public ToolOutcome call(ToolCall call) {
            try {
                CallToolResult result = client.callTool(new CallToolRequest(call.name(), call.arguments()));
                String text = result.content().stream()
                        .filter(TextContent.class::isInstance)
                        .map(content -> ((TextContent) content).text())
                        .collect(Collectors.joining("\n"));
                return new ToolOutcome(call.id(), text.isBlank() ? "(빈 결과)" : text, Boolean.TRUE.equals(result.isError()));
            } catch (RuntimeException e) {
                // 연결 끊김·타임아웃·인자 스키마 위반 등. 모델이 읽고 사용자에게 설명하도록 결과로 돌려준다
                log.warn("도구 호출 실패: tool={}", call.name(), e);
                return ToolOutcome.error(call.id(), "도구를 실행하지 못했습니다: " + e.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                client.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("도구 서버 연결 종료 실패 (무시)", e);
            }
        }
    }
}

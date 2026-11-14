package com.couponrush.mcpserver.config;

import com.couponrush.mcpserver.tool.EventTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공식 MCP Java SDK 를 Spring Boot 서블릿 컨테이너에 붙인다.
 * SDK 의 Streamable HTTP 전송(HttpServletStreamableServerTransportProvider)이 HttpServlet 이라 ServletRegistrationBean 으로 등록한다
 * — Spring MVC 컨트롤러가 아니므로 Spring AI 없이 SDK 그대로 쓴다. 도구 호출은 SDK 가 처리하고, 응답 스트림(SSE) 때문에 async 지원을 켠다.
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    static final String SERVER_NAME = "coupon-rush";
    static final String SERVER_VERSION = "0.1.0";

    /** 모델이 서버를 처음 볼 때 읽는 안내 (도구 선택·한계) */
    static final String INSTRUCTIONS = """
            선착순 쿠폰 이벤트를 조회하는 도구 서버입니다. 모든 도구는 조회 전용입니다.
            사용자가 이벤트를 찾으면 search_events 에 사용자의 질문을 그대로 넘기고, 특정 이벤트를 자세히 보려면 get_event 를 씁니다.
            쿠폰 발급은 이 서버로 할 수 없습니다. 발급을 원하면 결과의 url(이벤트 페이지)을 안내하세요.
            남은 수량은 조회 시점 값이며 선착순이라 금방 바뀔 수 있습니다.""";

    /**
     * MCP 프로토콜용 JSON 매퍼는 Spring 의 ObjectMapper 와 따로 둔다 — spring.jackson.* 설정이 프로토콜 메시지 형식을 바꾸지 않게.
     * (도구 결과 본문은 Spring ObjectMapper 로 만든다: 시각 형식 등이 다른 서비스 응답과 같도록)
     */
    @Bean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(new ObjectMapper());
    }

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper mcpJsonMapper, McpProperties properties) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .mcpEndpoint(properties.endpoint())
                .httpHeaderValidator(headerValidator(properties))
                .build();
    }

    /**
     * DNS 리바인딩 방지 (MCP 명세 권고): 악성 웹페이지가 브라우저를 이용해 localhost 의 MCP 서버를 부르면 Host 가 공격자 도메인이 된다
     * → 허용 목록에 없는 Host 는 거절. 목록이 비면 검사하지 않는다 (경고).
     */
    private static ServerHttpHeaderValidator headerValidator(McpProperties properties) {
        if (properties.allowedHosts().isEmpty()) {
            log.warn("app.mcp.allowed-hosts 가 비어 있어 Host 헤더를 검사하지 않습니다 (DNS 리바인딩 방지 꺼짐)");
            return ServerHttpHeaderValidator.NOOP;
        }
        return DefaultServerTransportSecurityValidator.builder().allowedHosts(properties.allowedHosts()).build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport, McpProperties properties) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, properties.endpoint());
        registration.setName("mcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, McpJsonMapper mcpJsonMapper, EventTools eventTools) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions(INSTRUCTIONS)
                .jsonMapper(mcpJsonMapper)
                .capabilities(ServerCapabilities.builder().tools(false).build())   // 도구 목록이 실행 중에 바뀌지 않는다 (listChanged=false)
                .tools(eventTools.specifications(mcpJsonMapper))
                .build();
    }
}

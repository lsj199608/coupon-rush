package com.couponrush.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP 도구 서버 (7단계 B). AI 클라이언트가 쿠폰 이벤트를 조회 전용 도구로 쓴다.
 * <pre>
 * [Claude Code 등 MCP 클라이언트] ─ Streamable HTTP /mcp ─▶ [mcp-server] ─ REST ─▶ [coupon-api] → DB
 * </pre>
 * 발급 도구는 없다 — "쿠폰 발급 핵심 경로에 LLM 을 넣지 않는다" 규칙. 결과에 이벤트 페이지 링크를 넣어 발급은 사람이 화면에서 한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}

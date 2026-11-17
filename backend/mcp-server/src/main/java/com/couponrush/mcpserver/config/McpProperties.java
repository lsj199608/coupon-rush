package com.couponrush.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * mcp-server 설정 (app.mcp).
 *
 * @param endpoint        MCP Streamable HTTP 경로
 * @param allowedHosts    받을 Host 헤더 (DNS 리바인딩 방지). 비우면 검사하지 않는다 (경고 로그)
 * @param couponApi       coupon-api 호출 설정
 * @param search          검색 도구가 PENDING 을 기다리는 방식
 * @param frontendBaseUrl 결과에 넣을 이벤트 페이지 링크의 앞부분
 * @param auth            서비스 호출(챗봇 서버) 인증
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        @DefaultValue("/mcp") String endpoint,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue CouponApi couponApi,
        @DefaultValue Search search,
        @DefaultValue("http://localhost:5173") String frontendBaseUrl,
        @DefaultValue Auth auth) {

    /**
     * @param internalToken coupon-api 내부 API(/internal/**)용 X-Internal-Token (INTERNAL_API_TOKEN). 서비스 호출의 "사용자 쿠폰"에만 쓴다.
     *                      비우면 그 기능만 꺼진다 (사람이 Bearer 로 쓰는 도구는 그대로)
     */
    public record CouponApi(@DefaultValue("http://localhost:8080") String baseUrl,
                            @DefaultValue("2s") Duration connectTimeout,
                            @DefaultValue("5s") Duration readTimeout,
                            @DefaultValue("") String internalToken) {
    }

    /**
     * @param serviceToken 서버(llm-service)가 X-Mcp-Service-Token 으로 보내야 하는 값 (MCP_SERVICE_TOKEN). 비우면 서비스 호출을 받지 않는다
     */
    public record Auth(@DefaultValue("") String serviceToken) {
    }

    /**
     * @param waitTimeout  이 시간 안에 PENDING 이 끝나지 않으면 도구 오류 (coupon-api 마감 15초가 지나면 키워드 검색으로 끝나므로 보통 오지 않는다)
     * @param pollInterval 상태 조회 간격
     */
    public record Search(@DefaultValue("20s") Duration waitTimeout,
                         @DefaultValue("500ms") Duration pollInterval) {
    }
}

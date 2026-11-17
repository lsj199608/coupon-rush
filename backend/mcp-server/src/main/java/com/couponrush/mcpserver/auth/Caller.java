package com.couponrush.mcpserver.auth;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * 이 MCP 요청을 보낸 쪽. HTTP 헤더에서만 정해진다 (CallerAuthentication) — 도구 인자로는 절대 정하지 않는다.
 * 모델이 인자로 userId 를 고를 수 있으면 프롬프트 인젝션("사용자 7번 쿠폰 보여줘")으로 남의 정보를 볼 수 있기 때문이다.
 */
public sealed interface Caller {

    /** 요청마다 McpTransportContext 에 이 키로 담긴다 */
    String CONTEXT_KEY = "couponrush.caller";

    Caller ANONYMOUS = new Anonymous();

    /** 인증 헤더 없음 — 공개 도구(검색·상세)만 */
    record Anonymous() implements Caller {
    }

    /**
     * 사람이 쓰는 MCP 클라이언트(Claude Code 등): 로그인해서 받은 coupon-api access token. mcp-server 는 검증하지 않고 coupon-api 에 그대로 넘긴다
     * (만료·로그아웃 판정은 coupon-api 한 곳에서).
     */
    record User(String accessToken) implements Caller {
        @Override
        public String toString() {
            return "User[accessToken=***]";   // 로그에 토큰이 찍히지 않게
        }
    }

    /**
     * 서버(llm-service 챗봇): 서비스 토큰을 확인한 요청. userId 는 그 서버가 넣은 값 — coupon-api 가 JWT 로 확인한 사용자를
     * Kafka 메시지로 받아 넘긴다. 사용자 없이(userId null) 공개 도구만 쓸 수도 있다.
     */
    record Service(Long userId) implements Caller {
    }

    static Caller of(McpTransportContext context) {
        Object caller = context == null ? null : context.get(CONTEXT_KEY);
        return caller instanceof Caller known ? known : ANONYMOUS;
    }
}

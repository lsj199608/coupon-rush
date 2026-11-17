package com.couponrush.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 내부 API(/internal/**) 설정 (app.internal-api).
 *
 * @param token 다른 서비스(지금은 mcp-server)가 X-Internal-Token 헤더로 보내야 하는 값 (INTERNAL_API_TOKEN).
 *              비어 있으면 내부 API 는 꺼진다 — 어떤 요청도 통과하지 못한다 (설정을 빠뜨렸을 때 열리는 쪽이 아니라 닫히는 쪽)
 */
@ConfigurationProperties("app.internal-api")
public record InternalApiProperties(@DefaultValue("") String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }
}

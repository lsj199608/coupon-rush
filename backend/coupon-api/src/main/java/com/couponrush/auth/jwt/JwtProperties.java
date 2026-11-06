package com.couponrush.auth.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param secret               HMAC 서명 키 (32바이트 이상, 환경변수 JWT_SECRET)
 * @param accessTokenValidity  access token 유효기간 (기본 30m)
 * @param refreshTokenValidity refresh token 유효기간 (기본 14d) — Redis TTL 과 쿠키 Max-Age 도 이 값을 쓴다
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenValidity,
        @NotNull Duration refreshTokenValidity) {
}

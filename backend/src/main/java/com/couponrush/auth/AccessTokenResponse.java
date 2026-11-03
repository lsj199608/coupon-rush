package com.couponrush.auth;

/**
 * @param expiresIn access token 유효기간(초). 프론트엔드가 만료 전에 미리 refresh 할 때 쓴다
 */
public record AccessTokenResponse(String accessToken, long expiresIn) {
}

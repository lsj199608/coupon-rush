package com.couponrush.auth.jwt;

import com.couponrush.user.Role;

import java.time.Instant;

/**
 * @param tokenId   jti — 로그아웃 시 블랙리스트 키로 쓴다
 * @param expiresAt 블랙리스트 TTL(남은 유효시간) 계산용
 */
public record AccessTokenClaims(Long userId, Role role, String tokenId, Instant expiresAt) {
}

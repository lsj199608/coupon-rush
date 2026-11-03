package com.couponrush.auth.jwt;

import java.time.Instant;

public record RefreshTokenClaims(Long userId, Instant expiresAt) {
}

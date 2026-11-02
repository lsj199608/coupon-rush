package com.couponrush.auth.jwt;

import com.couponrush.user.Role;

public record AccessTokenClaims(Long userId, Role role) {
}

package com.couponrush.auth.jwt;

import com.couponrush.user.Role;

import java.time.Instant;

/**
 * Bearer 토큰으로 인증된 요청의 principal. 컨트롤러에서 @AuthenticationPrincipal AuthUser 로 받는다.
 * 로그아웃 시 현재 토큰을 블랙리스트에 넣어야 해서 tokenId, tokenExpiresAt 도 들고 있다.
 */
public record AuthUser(Long userId, Role role, String tokenId, Instant tokenExpiresAt) {

    public static AuthUser from(AccessTokenClaims claims) {
        return new AuthUser(claims.userId(), claims.role(), claims.tokenId(), claims.expiresAt());
    }
}

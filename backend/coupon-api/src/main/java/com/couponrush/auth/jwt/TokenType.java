package com.couponrush.auth.jwt;

/**
 * 토큰 용도. 둘 다 같은 키로 서명하므로 claim 으로 구분하지 않으면
 * 14일짜리 refresh token 을 Bearer access token 으로 쓸 수 있게 된다.
 */
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }
}

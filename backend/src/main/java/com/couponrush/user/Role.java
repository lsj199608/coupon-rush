package com.couponrush.user;

public enum Role {
    USER,
    ADMIN;

    /** Spring Security 권한 문자열 (hasRole("USER") 와 매칭) */
    public String authority() {
        return "ROLE_" + name();
    }
}

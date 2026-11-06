package com.couponrush.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secure refresh token 쿠키 Secure 속성 (HTTPS 에서만 전송). 로컬 http 개발 시 false
 */
@ConfigurationProperties("app.auth.cookie")
public record AuthCookieProperties(@DefaultValue("false") boolean secure) {
}

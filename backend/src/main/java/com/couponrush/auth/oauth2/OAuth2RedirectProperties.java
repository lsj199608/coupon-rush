package com.couponrush.auth.oauth2;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param redirectUri 로그인 성공/실패 후 이동할 프론트엔드 주소
 */
@Validated
@ConfigurationProperties("app.oauth2")
public record OAuth2RedirectProperties(@NotBlank String redirectUri) {
}

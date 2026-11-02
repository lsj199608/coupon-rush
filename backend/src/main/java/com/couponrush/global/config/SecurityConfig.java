package com.couponrush.global.config;

import com.couponrush.auth.oauth2.CustomOAuth2UserService;
import com.couponrush.auth.oauth2.OAuth2LoginFailureHandler;
import com.couponrush.auth.oauth2.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * - 헬스 체크만 공개, 나머지는 인증 필요 (기본값이 '전부 차단'이어야 실수로 열리는 API가 없다)
 * - JWT 기반이라 세션/CSRF/폼로그인은 끈다
 *   (OAuth2 인가 요청의 state 보관에만 세션을 잠깐 쓰고, 성공 핸들러에서 정리한다)
 * - 로그인 진입: GET /oauth2/authorization/kakao  → 콜백: /login/oauth2/code/kakao (Spring 이 처리)
 */
@Configuration
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler successHandler;
    private final OAuth2LoginFailureHandler failureHandler;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                          OAuth2LoginSuccessHandler successHandler,
                          OAuth2LoginFailureHandler failureHandler) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                // API 서버이므로 미인증 요청은 로그인 페이지 리다이렉트(302)가 아니라 401 로 응답
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}

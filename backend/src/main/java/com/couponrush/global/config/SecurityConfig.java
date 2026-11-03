package com.couponrush.global.config;

import com.couponrush.auth.jwt.JsonAuthenticationEntryPoint;
import com.couponrush.auth.jwt.JwtAuthenticationFilter;
import com.couponrush.auth.jwt.JwtTokenProvider;
import com.couponrush.auth.oauth2.CustomOAuth2UserService;
import com.couponrush.auth.oauth2.OAuth2LoginFailureHandler;
import com.couponrush.auth.oauth2.OAuth2LoginSuccessHandler;
import com.couponrush.auth.token.AccessTokenBlacklist;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * - 기본은 '전부 인증 필요'. 공개 경로만 명시적으로 연다 (실수로 열리는 API 방지)
 * - JWT 기반이라 세션/CSRF/폼로그인은 끈다
 *   (OAuth2 인가 요청의 state 보관에만 세션을 잠깐 쓰고, 성공 핸들러에서 정리한다.
 *    refresh/logout 의 쿠키는 SameSite=Strict 로 CSRF 를 막는다)
 * - 로그인 진입: GET /oauth2/authorization/{kakao|naver|google}
 * - 보호된 API: Authorization: Bearer {access token} → JwtAuthenticationFilter 가 검증
 */
@Configuration
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler successHandler;
    private final OAuth2LoginFailureHandler failureHandler;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                          OAuth2LoginSuccessHandler successHandler,
                          OAuth2LoginFailureHandler failureHandler,
                          JsonAuthenticationEntryPoint authenticationEntryPoint,
                          JwtTokenProvider jwtTokenProvider,
                          AccessTokenBlacklist accessTokenBlacklist) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.jwtTokenProvider = jwtTokenProvider;
        this.accessTokenBlacklist = accessTokenBlacklist;
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
                        // access token 이 만료된 상태에서 호출하는 API 라 인증 없이 허용 (refresh token 쿠키로 검증)
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, accessTokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class)
                // API 서버이므로 미인증 요청은 로그인 페이지 리다이렉트(302)가 아니라 401 JSON 으로 응답
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
                .build();
    }
}

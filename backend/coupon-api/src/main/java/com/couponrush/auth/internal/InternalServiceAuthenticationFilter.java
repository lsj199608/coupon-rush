package com.couponrush.auth.internal;

import com.couponrush.auth.jwt.JwtAuthenticationFilter;
import com.couponrush.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 서비스 간 호출 인증: /internal/** 요청의 X-Internal-Token 이 설정값(INTERNAL_API_TOKEN)과 같으면 ROLE_INTERNAL_SERVICE 로 인증한다.
 * <ul>
 *   <li>/internal/** 에서만 동작한다 — 이 토큰으로 사용자 API(/api/**)를 쓸 수 없다 (토큰 하나가 뚫려도 피해 범위를 내부 API 로 한정)</li>
 *   <li>사용자 JWT 로는 /internal/** 에 들어올 수 없다 (역할이 USER/ADMIN 이라 403) — 내부 API 는 "어느 사용자인지"를 경로로 받기 때문에
 *       사용자가 직접 부르면 남의 쿠폰을 볼 수 있게 된다</li>
 *   <li>토큰이 설정되지 않았으면 아무도 인증하지 않는다 (닫힌 상태가 기본)</li>
 *   <li>비교는 MessageDigest.isEqual (걸리는 시간으로 토큰을 한 글자씩 맞혀 보는 공격 방지)</li>
 * </ul>
 * JwtAuthenticationFilter 와 같이 직접 응답하지 않고, 실패 사유만 남겨 entry point 가 401 을 낸다. @Component 로 등록하지 않는다 (두 번 실행 방지).
 */
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";
    public static final String INTERNAL_PATH_PREFIX = "/internal/";
    public static final String ROLE = "INTERNAL_SERVICE";

    private final byte[] expectedToken;

    public InternalServiceAuthenticationFilter(InternalApiProperties properties) {
        this.expectedToken = properties.enabled() ? properties.token().getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null) {
            if (matches(token)) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            } else {
                SecurityContextHolder.clearContext();
                request.setAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE, ErrorCode.UNAUTHORIZED);
            }
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String token) {
        return expectedToken != null && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }
}

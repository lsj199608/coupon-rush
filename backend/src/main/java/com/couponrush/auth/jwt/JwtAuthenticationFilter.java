package com.couponrush.auth.jwt;

import com.couponrush.auth.token.AccessTokenBlacklist;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer {access token} 을 검증해 SecurityContext 에 인증 정보를 넣는다.
 * - 토큰이 없거나 잘못돼도 여기서 바로 응답하지 않고 다음 필터로 넘긴다.
 *   → permitAll 경로(/api/auth/refresh 등)는 만료된 토큰이 붙어 와도 정상 동작하고,
 *     보호된 경로는 Spring Security 가 entry point 로 401 을 낸다.
 * - 실패 사유는 request attribute 로 남겨 entry point 가 TOKEN_EXPIRED 등 구체적인 코드로 응답하게 한다.
 * - @Component 로 등록하지 않는다: 서블릿 필터로 자동 등록돼 두 번 실행되는 것을 막기 위함 (SecurityConfig 에서 생성)
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".ERROR";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklist blacklist;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, AccessTokenBlacklist blacklist) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                AccessTokenClaims claims = jwtTokenProvider.parseAccessToken(header.substring(BEARER_PREFIX.length()).trim());
                if (blacklist.contains(claims.tokenId())) {
                    throw new BusinessException(ErrorCode.TOKEN_REVOKED);
                }
                UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                        AuthUser.from(claims), null, List.of(new SimpleGrantedAuthority(claims.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BusinessException e) {
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
            }
        }

        chain.doFilter(request, response);
    }
}

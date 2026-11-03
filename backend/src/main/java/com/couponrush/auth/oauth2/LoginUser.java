package com.couponrush.auth.oauth2;

import com.couponrush.user.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 로그인 직후의 인증 주체. 제공자 ID 가 아니라 우리 서비스의 userId 를 담아
 * 성공 핸들러가 DB 를 다시 조회하지 않고 바로 JWT 를 만들 수 있게 한다.
 */
public class LoginUser implements OAuth2User {

    private final Long userId;
    private final Role role;
    private final Map<String, Object> attributes;

    public LoginUser(Long userId, Role role, Map<String, Object> attributes) {
        this.userId = userId;
        this.role = role;
        // Map.copyOf 는 null 값을 거부한다. 제공자 응답 JSON 에는 null 필드가 올 수 있어 LinkedHashMap 으로 복사
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}

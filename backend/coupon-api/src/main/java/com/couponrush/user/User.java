package com.couponrush.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 회원. "user" 는 PostgreSQL 예약어라 테이블명은 users.
 * 식별 키는 (provider, providerId) — 닉네임은 사용자가 바꿀 수 있어 식별에 쓰지 않고 로그인마다 갱신한다.
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(
        name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"}))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuth2Provider provider;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(nullable = false, length = 50)
    private String nickname;

    /** 제공자/동의 여부에 따라 없을 수 있다 */
    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    private User(OAuth2Provider provider, String providerId, String nickname, String email, Role role) {
        this.provider = provider;
        this.providerId = providerId;
        this.nickname = nickname;
        this.email = email;
        this.role = role;
    }

    public static User createSocialUser(OAuth2Provider provider, String providerId, String nickname, String email) {
        return new User(provider, providerId, nickname, email, Role.USER);
    }

    /** 로그인 시 제공자의 최신 프로필로 갱신. 이메일은 새로 받은 값이 있을 때만 덮어쓴다. */
    public void updateProfile(String nickname, String email) {
        this.nickname = nickname;
        if (email != null) {
            this.email = email;
        }
    }

    public Long getId() { return id; }
    public OAuth2Provider getProvider() { return provider; }
    public String getProviderId() { return providerId; }
    public String getNickname() { return nickname; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

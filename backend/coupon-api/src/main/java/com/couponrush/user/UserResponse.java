package com.couponrush.user;

/** 엔티티를 그대로 내보내지 않고 응답 전용 DTO 로 변환 (내부 필드 노출/지연 로딩 문제 방지) */
public record UserResponse(Long id, OAuth2Provider provider, String nickname, String email, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getProvider(), user.getNickname(), user.getEmail(), user.getRole());
    }
}

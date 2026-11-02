package com.couponrush.auth.oauth2.userinfo;

import com.couponrush.user.OAuth2Provider;

import java.util.Optional;

/**
 * 제공자마다 다른 사용자 정보 응답 구조를 공통 형태로 맞추는 추상화.
 * 새 제공자를 추가할 때는 이 인터페이스 구현체 + OAuth2UserInfoFactory 분기만 추가하면 된다.
 */
public interface OAuth2UserInfo {

    OAuth2Provider getProvider();

    /** 제공자 내 고유 ID (회원 식별 키) */
    String getProviderId();

    /** 동의하지 않았거나 값이 없으면 기본 닉네임을 돌려준다 */
    String getNickname();

    /** 이메일은 제공자/동의 여부에 따라 없을 수 있어 Optional */
    Optional<String> getEmail();
}

package com.couponrush.webchat.auth;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * D1 기본값: 모든 연결을 비로그인으로 둔다 → 읽기만 되고 보내기는 거절.
 * <p>
 * 헤더로 회원 ID 를 받는 "개발용" 인증을 두지 않은 이유: 켜진 채 배포되면 누구나 남을 사칭할 수 있다.
 * 보내기는 D2(티켓 인증)부터 실제로 동작하고, D1 의 보내기 흐름은 테스트에서 테스트용 인증기로 검증한다.
 */
@Component
public class AnonymousWebChatAuthenticator implements WebChatAuthenticator {

    @Override
    public Optional<WebChatUser> authenticate(StompHeaderAccessor connect) {
        return Optional.empty();
    }
}

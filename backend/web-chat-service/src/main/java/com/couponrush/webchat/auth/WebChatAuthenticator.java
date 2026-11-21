package com.couponrush.webchat.auth;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.Optional;

/**
 * STOMP CONNECT 프레임으로 회원을 정한다.
 * <ul>
 *   <li>회원 → 그 연결은 구독 + 보내기</li>
 *   <li>빈 값 → 비로그인 연결 (구독만)</li>
 *   <li>{@link com.couponrush.webchat.room.WebChatRejectedException} → 연결 거절 (D2: 만료·재사용 티켓)</li>
 * </ul>
 * D1 은 {@link AnonymousWebChatAuthenticator}(모두 비로그인). D2 에서 coupon-api 가 발급한 1회용 티켓을 확인하는 구현으로 바꾼다
 * — 브라우저 WebSocket 은 Authorization 헤더를 못 붙이고, URL 에 토큰을 싣지 않는 규칙이라 첫 STOMP 프레임으로 받는다.
 */
public interface WebChatAuthenticator {

    Optional<WebChatUser> authenticate(StompHeaderAccessor connect);
}

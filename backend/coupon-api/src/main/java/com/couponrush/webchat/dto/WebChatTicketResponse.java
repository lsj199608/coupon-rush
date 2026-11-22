package com.couponrush.webchat.dto;

import java.time.Instant;

/**
 * @param ticket    STOMP CONNECT 의 ticket 헤더에 넣을 값 (1회용, 연결하면 사라짐)
 * @param expiresAt 이 시각이 지나면 쓸 수 없다 — 받자마자 연결하는 용도
 */
public record WebChatTicketResponse(String ticket, Instant expiresAt) {
}

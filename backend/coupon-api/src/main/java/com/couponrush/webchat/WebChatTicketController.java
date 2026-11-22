package com.couponrush.webchat;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.webchat.dto.WebChatTicketResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/web-chat/tickets (로그인 필요 — SecurityConfig 기본 규칙) → 채팅방 연결용 1회용 티켓.
 * 연결(ws /ws/web-chat)할 때마다 새로 받는다 — 다시 연결(자동 재연결 포함)도 새 티켓으로.
 * 비로그인은 티켓 없이 연결해 읽기만 한다.
 */
@RestController
public class WebChatTicketController {

    private final WebChatTicketService ticketService;

    public WebChatTicketController(WebChatTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 티켓은 비밀값이라 어떤 캐시에도 남기지 않는다 (no-store). */
    @PostMapping("/api/web-chat/tickets")
    public ResponseEntity<WebChatTicketResponse> issue(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ticketService.issue(authUser.userId()));
    }
}

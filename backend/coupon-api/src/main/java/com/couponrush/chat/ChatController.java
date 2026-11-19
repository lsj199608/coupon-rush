package com.couponrush.chat;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.chat.dto.ChatMessageRequest;
import com.couponrush.chat.dto.ChatSessionResponse;
import com.couponrush.chat.dto.ChatSessionSummaryResponse;
import com.couponrush.chat.dto.ChatTurnResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 챗봇 대화 API (7단계 C1, 로그인 필요 — SecurityConfig 기본 규칙). 남의 대화방·턴은 404.
 * <ul>
 *   <li>POST /api/chat/sessions → 201 새 대화방</li>
 *   <li>GET  /api/chat/sessions → 내 대화방 목록 (최근 순 20개)</li>
 *   <li>GET  /api/chat/sessions/{id} → 대화방 + 최근 턴</li>
 *   <li>POST /api/chat/sessions/{id}/turns {message} → 202 PENDING 턴 + Location (답은 폴링)</li>
 *   <li>GET  /api/chat/sessions/{id}/turns/{turnId} → 턴 상태 (PENDING / COMPLETED / FAILED)</li>
 * </ul>
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/api/chat/sessions")
    public ResponseEntity<ChatSessionResponse> open(@AuthenticationPrincipal AuthUser authUser) {
        ChatSessionResponse session = chatService.openSession(authUser.userId());
        return ResponseEntity.created(URI.create("/api/chat/sessions/" + session.sessionId())).body(session);
    }

    @GetMapping("/api/chat/sessions")
    public List<ChatSessionSummaryResponse> list(@AuthenticationPrincipal AuthUser authUser) {
        return chatService.listSessions(authUser.userId());
    }

    @GetMapping("/api/chat/sessions/{sessionId}")
    public ChatSessionResponse get(@PathVariable Long sessionId, @AuthenticationPrincipal AuthUser authUser) {
        return chatService.getSession(sessionId, authUser.userId());
    }

    @PostMapping("/api/chat/sessions/{sessionId}/turns")
    public ResponseEntity<ChatTurnResponse> ask(@PathVariable Long sessionId, @Valid @RequestBody ChatMessageRequest request,
                                                @AuthenticationPrincipal AuthUser authUser) {
        ChatTurnResponse turn = chatService.ask(sessionId, authUser.userId(), request.normalizedMessage());
        return ResponseEntity.accepted()
                .location(URI.create("/api/chat/sessions/" + sessionId + "/turns/" + turn.turnId()))
                .body(turn);
    }

    @GetMapping("/api/chat/sessions/{sessionId}/turns/{turnId}")
    public ChatTurnResponse getTurn(@PathVariable Long sessionId, @PathVariable Long turnId,
                                    @AuthenticationPrincipal AuthUser authUser) {
        return chatService.getTurn(sessionId, turnId, authUser.userId());
    }
}

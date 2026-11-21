package com.couponrush.chatbot;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.chatbot.dto.ChatbotMessageRequest;
import com.couponrush.chatbot.dto.ChatbotSessionResponse;
import com.couponrush.chatbot.dto.ChatbotSessionSummaryResponse;
import com.couponrush.chatbot.dto.ChatbotTurnResponse;
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
 *   <li>POST /api/chatbot/sessions → 201 새 대화방</li>
 *   <li>GET  /api/chatbot/sessions → 내 대화방 목록 (최근 순 20개)</li>
 *   <li>GET  /api/chatbot/sessions/{id} → 대화방 + 최근 턴</li>
 *   <li>POST /api/chatbot/sessions/{id}/turns {message} → 202 PENDING 턴 + Location (답은 폴링)</li>
 *   <li>GET  /api/chatbot/sessions/{id}/turns/{turnId} → 턴 상태 (PENDING / COMPLETED / FAILED)</li>
 * </ul>
 */
@RestController
public class ChatbotController {

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostMapping("/api/chatbot/sessions")
    public ResponseEntity<ChatbotSessionResponse> open(@AuthenticationPrincipal AuthUser authUser) {
        ChatbotSessionResponse session = chatbotService.openSession(authUser.userId());
        return ResponseEntity.created(URI.create("/api/chatbot/sessions/" + session.sessionId())).body(session);
    }

    @GetMapping("/api/chatbot/sessions")
    public List<ChatbotSessionSummaryResponse> list(@AuthenticationPrincipal AuthUser authUser) {
        return chatbotService.listSessions(authUser.userId());
    }

    @GetMapping("/api/chatbot/sessions/{sessionId}")
    public ChatbotSessionResponse get(@PathVariable Long sessionId, @AuthenticationPrincipal AuthUser authUser) {
        return chatbotService.getSession(sessionId, authUser.userId());
    }

    @PostMapping("/api/chatbot/sessions/{sessionId}/turns")
    public ResponseEntity<ChatbotTurnResponse> ask(@PathVariable Long sessionId, @Valid @RequestBody ChatbotMessageRequest request,
                                                @AuthenticationPrincipal AuthUser authUser) {
        ChatbotTurnResponse turn = chatbotService.ask(sessionId, authUser.userId(), request.normalizedMessage());
        return ResponseEntity.accepted()
                .location(URI.create("/api/chatbot/sessions/" + sessionId + "/turns/" + turn.turnId()))
                .body(turn);
    }

    @GetMapping("/api/chatbot/sessions/{sessionId}/turns/{turnId}")
    public ChatbotTurnResponse getTurn(@PathVariable Long sessionId, @PathVariable Long turnId,
                                    @AuthenticationPrincipal AuthUser authUser) {
        return chatbotService.getTurn(sessionId, turnId, authUser.userId());
    }
}

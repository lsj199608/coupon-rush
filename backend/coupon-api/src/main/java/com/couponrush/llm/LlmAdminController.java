package com.couponrush.llm;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.llm.dto.EventCopyRequest;
import com.couponrush.llm.dto.LlmRequestAcceptedResponse;
import com.couponrush.llm.dto.LlmRequestResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 관리자 LLM 기능. /api/admin/** 라서 SecurityConfig 의 ROLE_ADMIN 규칙에 그대로 포함된다 (추가 설정 없음).
 * 사용자 인증·권한은 여기서 끝난다 — llm-service 로는 JWT 가 아니라 requestedBy(userId)만 넘어간다.
 */
@RestController
@RequestMapping("/api/admin/llm")
public class LlmAdminController {

    private final LlmRequestService llmRequestService;

    public LlmAdminController(LlmRequestService llmRequestService) {
        this.llmRequestService = llmRequestService;
    }

    /** 이벤트 문구 생성 요청 → 202 + requestId. 결과는 저장되지 않는 제안이다 (관리자가 골라 고친 뒤 이벤트 생성 시 저장) */
    @PostMapping("/event-copy")
    public ResponseEntity<LlmRequestAcceptedResponse> requestEventCopy(@Valid @RequestBody EventCopyRequest request,
                                                                       @AuthenticationPrincipal AuthUser authUser) {
        LlmRequestAcceptedResponse accepted =
                llmRequestService.requestEventCopy(authUser.userId(), request.toInput(), request.regenerate());
        return ResponseEntity.accepted()
                .location(URI.create("/api/admin/llm/requests/" + accepted.requestId()))
                .body(accepted);
    }

    /** 상태 폴링: PENDING → COMPLETED(후보) / FAILED(사유) */
    @GetMapping("/requests/{requestId}")
    public LlmRequestResponse get(@PathVariable UUID requestId, @AuthenticationPrincipal AuthUser authUser) {
        return llmRequestService.get(requestId, authUser.userId());
    }
}

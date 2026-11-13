package com.couponrush.search;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.search.dto.EventSearchRequest;
import com.couponrush.search.dto.EventSearchResponse;
import com.couponrush.search.dto.EventSearchState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 자연어 이벤트 검색 (공개 — 목록이 공개이므로). 로그인했으면 회원별, 아니면 IP별로 한도를 센다.
 * <ul>
 *   <li>POST /api/coupons/search → 202 PENDING + Location (AI 조건 생성 중) 또는 200 FALLBACK (바로 키워드 검색 결과)</li>
 *   <li>GET /api/coupons/search/{requestId} → 200 PENDING / COMPLETED / FALLBACK</li>
 * </ul>
 * 질문이 조회를 바꾸지 않는데 POST 인 이유: 요청 행·LLM 호출(비용)이 생기는 동작이라 GET(안전·캐시 가능)으로 두지 않는다.
 */
@RestController
public class EventSearchController {

    private final EventSearchService eventSearchService;

    public EventSearchController(EventSearchService eventSearchService) {
        this.eventSearchService = eventSearchService;
    }

    /**
     * @param authUser 비로그인이면 null. IP 는 getRemoteAddr — 리버스 프록시 뒤에 배포하면 server.forward-headers-strategy 로
     *                 신뢰하는 프록시의 X-Forwarded-For 를 쓰게 해야 한다 (아니면 모든 비로그인이 프록시 주소 하나를 나눠 쓴다)
     */
    @PostMapping("/api/coupons/search")
    public ResponseEntity<EventSearchResponse> search(@Valid @RequestBody EventSearchRequest request,
                                                      @AuthenticationPrincipal AuthUser authUser,
                                                      HttpServletRequest servletRequest) {
        EventSearchResponse response = eventSearchService.search(request.normalizedQuery(),
                authUser != null ? authUser.userId() : null, servletRequest.getRemoteAddr());
        if (response.status() == EventSearchState.PENDING) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/coupons/search/" + response.requestId()))
                    .body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/coupons/search/{requestId}")
    public EventSearchResponse get(@PathVariable UUID requestId) {
        return eventSearchService.get(requestId);
    }
}

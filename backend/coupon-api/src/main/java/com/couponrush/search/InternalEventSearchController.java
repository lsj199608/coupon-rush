package com.couponrush.search;

import com.couponrush.search.dto.EventSearchRequest;
import com.couponrush.search.dto.EventSearchResponse;
import com.couponrush.search.dto.EventSearchState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 서비스 간 내부 API (7단계 C2) — 챗봇이 회원 대신 검색할 때 (llm-service → mcp-server → 여기).
 * X-Internal-Token 이 맞을 때만 열린다 (/internal/** = ROLE_INTERNAL_SERVICE, 사용자 JWT 로는 403).
 * <p>
 * 공개 검색(POST /api/coupons/search)과 결과·폴링 경로가 같고, 다른 점은 <b>한도를 경로의 회원으로 센다</b>는 것뿐이다.
 * 공개 검색으로 부르면 mcp-server 한 대가 모든 회원의 검색을 비로그인 IP(127.0.0.1) 한도 하나로 나눠 쓰게 된다.
 */
@RestController
public class InternalEventSearchController {

    private final EventSearchService eventSearchService;

    public InternalEventSearchController(EventSearchService eventSearchService) {
        this.eventSearchService = eventSearchService;
    }

    @PostMapping("/internal/users/{userId}/coupons/search")
    public ResponseEntity<EventSearchResponse> search(@PathVariable Long userId, @Valid @RequestBody EventSearchRequest request,
                                                      HttpServletRequest servletRequest) {
        EventSearchResponse response = eventSearchService.search(request.normalizedQuery(), userId, servletRequest.getRemoteAddr());
        if (response.status() == EventSearchState.PENDING) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/coupons/search/" + response.requestId()))
                    .body(response);
        }
        return ResponseEntity.ok(response);
    }
}

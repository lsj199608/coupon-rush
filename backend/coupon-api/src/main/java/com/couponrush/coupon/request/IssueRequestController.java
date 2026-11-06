package com.couponrush.coupon.request;

import com.couponrush.auth.jwt.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IssueRequestController {

    private final IssueRequestService issueRequestService;

    public IssueRequestController(IssueRequestService issueRequestService) {
        this.issueRequestService = issueRequestService;
    }

    /** 비동기 발급 요청 상태 (PENDING → COMPLETED / FAILED). 202 응답의 Location 이 이 주소다 */
    @GetMapping("/api/issue-requests/{requestId}")
    public IssueRequestResponse get(@PathVariable String requestId, @AuthenticationPrincipal AuthUser authUser) {
        return issueRequestService.get(requestId, authUser.userId());
    }
}

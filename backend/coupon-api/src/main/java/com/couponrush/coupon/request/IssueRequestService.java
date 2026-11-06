package com.couponrush.coupon.request;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 요청 상태 조회. 최종 결과(DB)를 먼저 보고, 없으면 처리 대기(Redis)를 본다.
 * 남의 요청은 존재 여부도 알려주지 않는다 (404) — requestId 를 추측해 다른 사람의 발급 여부를 알아내지 못하게.
 */
@Service
public class IssueRequestService {

    private final IssueRequestRepository issueRequestRepository;
    private final PendingIssueRequestStore pendingStore;

    public IssueRequestService(IssueRequestRepository issueRequestRepository, PendingIssueRequestStore pendingStore) {
        this.issueRequestRepository = issueRequestRepository;
        this.pendingStore = pendingStore;
    }

    @Transactional(readOnly = true)
    public IssueRequestResponse get(String requestId, Long userId) {
        return issueRequestRepository.findById(requestId)
                .filter(request -> request.getUserId().equals(userId))
                .map(IssueRequestResponse::from)
                .or(() -> pendingStore.find(requestId)
                        .filter(pending -> pending.userId().equals(userId))
                        .map(IssueRequestResponse::pending))
                .orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_REQUEST_NOT_FOUND));
    }
}

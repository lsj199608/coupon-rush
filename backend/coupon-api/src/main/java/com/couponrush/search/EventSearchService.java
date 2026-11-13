package com.couponrush.search;

import com.couponrush.coupon.CouponService;
import com.couponrush.coupon.CouponStatus;
import com.couponrush.coupon.search.CouponSearchCondition;
import com.couponrush.global.error.BusinessException;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestService;
import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.dto.LlmRequestAcceptedResponse;
import com.couponrush.search.dto.EventSearchResponse;
import com.couponrush.search.dto.SearchFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * [자연어 이벤트 검색] AI 는 질문 → 조건만 만들고(llm-service), 조회는 항상 여기서 DB 로 한다 — 없는 이벤트를 지어낼 수 없다.
 * <pre>
 * 접수: 예산·한도 OK → LLM 요청(PENDING) → 202, 화면이 폴링
 *       예산 소진·한도 초과·발행 실패·그 밖의 오류 → 그 자리에서 키워드 검색 (200 FALLBACK)
 * 조회: PENDING → 그대로 / COMPLETED → AI 조건으로 DB 조회 / FAILED(타임아웃·LLM 실패) → 키워드 검색
 * </pre>
 * <b>검색은 AI 없이도 동작해야 한다</b> — LLM 쪽 문제는 에러 응답이 아니라 키워드 검색 결과 + 사유가 된다.
 * 입력 검증(빈 질문, 200자 초과)만 400 이다.
 * <p>
 * 발급 경로와 무관하다 (coupon 패키지의 조회 기능만 쓴다 — "발급 핵심 경로에 LLM 금지" 규칙).
 */
@Service
public class EventSearchService {

    private static final Logger log = LoggerFactory.getLogger(EventSearchService.class);

    private final LlmRequestService llmRequestService;
    private final CouponService couponService;

    public EventSearchService(LlmRequestService llmRequestService, CouponService couponService) {
        this.llmRequestService = llmRequestService;
        this.couponService = couponService;
    }

    /**
     * @param userId   로그인했으면 회원 ID, 아니면 null
     * @param clientIp 비로그인 한도를 세는 주소
     */
    public EventSearchResponse search(String query, Long userId, String clientIp) {
        long requestedBy = userId != null ? userId : LlmRequestService.ANONYMOUS;
        String rateSubject = userId != null ? "user:" + userId : "ip:" + clientIp;
        try {
            LlmRequestAcceptedResponse accepted =
                    llmRequestService.requestEventSearch(requestedBy, rateSubject, new EventSearchInput(query));
            return EventSearchResponse.pending(accepted.requestId(), query, accepted.deadlineAt());
        } catch (BusinessException e) {
            return fallback(null, query, reasonOf(e));
        } catch (RuntimeException e) {
            // Redis(한도·예산 확인) 장애 등. 검색 자체는 DB 로 계속 된다
            log.warn("AI 검색 접수 실패 — 키워드 검색으로 대신: query 길이={}", query.length(), e);
            return fallback(null, query, SearchFallbackReason.LLM_UNAVAILABLE);
        }
    }

    public EventSearchResponse get(UUID requestId) {
        LlmRequest request = llmRequestService.getSearch(requestId);
        String query = llmRequestService.searchQueryOf(request);
        return switch (request.getStatus()) {
            case PENDING -> EventSearchResponse.pending(requestId, query, request.getDeadlineAt());
            case COMPLETED -> {
                EventSearchFilter filter = llmRequestService.searchFilterOf(request);
                yield EventSearchResponse.completed(requestId, query, filter, couponService.search(toCondition(filter)));
            }
            case FAILED -> fallback(requestId, query, SearchFallbackReason.of(request.getFailureReason()));
        };
    }

    private EventSearchResponse fallback(UUID requestId, String query, SearchFallbackReason reason) {
        List<String> keywords = KeywordQuery.keywordsOf(query);
        return EventSearchResponse.fallback(requestId, query, reason, keywords,
                couponService.search(CouponSearchCondition.anyKeyword(keywords)));
    }

    /** 접수 단계 거절 → 사유. 한도·예산 외의 BusinessException 은 발행 실패(LLM_REQUEST_UNAVAILABLE)뿐이다 */
    private static SearchFallbackReason reasonOf(BusinessException e) {
        return switch (e.getErrorCode()) {
            case LLM_RATE_LIMITED, LLM_DAILY_LIMIT_EXCEEDED -> SearchFallbackReason.RATE_LIMITED;
            case LLM_BUDGET_EXCEEDED -> SearchFallbackReason.BUDGET_EXCEEDED;
            default -> SearchFallbackReason.LLM_UNAVAILABLE;
        };
    }

    /** 계약의 조건 → coupon 패키지의 조건. 상태 이름은 같다 (CouponTest 가 검사) */
    static CouponSearchCondition toCondition(EventSearchFilter filter) {
        return new CouponSearchCondition(filter.keywords(), CouponSearchCondition.KeywordMatch.ALL,
                filter.statuses().stream().map(status -> CouponStatus.valueOf(status.name())).collect(Collectors.toSet()),
                filter.startFrom(), filter.startTo(), filter.endFrom(), filter.endTo(), filter.availableOnly());
    }
}

package com.couponrush.coupon.issue.async;

import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.coupon.issue.CouponIssueSupport;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.request.IssueRequest;
import com.couponrush.coupon.request.IssueRequestRepository;
import com.couponrush.coupon.request.PendingIssueRequestStore;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * [비동기 발급 — 처리 경로] 발급 이벤트 하나를 DB 에 반영한다.
 *
 * 1) 멱등: issue_requests 에 이미 결과가 있으면 건너뛴다 (Kafka 는 at-least-once — 같은 메시지가 다시 올 수 있다)
 *    사전 확인을 동시에 통과한 중복(리밸런싱 직후 등)은 유니크 제약이 막는다 → 그 위반이 "같은 요청이 이미 처리됨"이면 정상으로 본다
 * 2) 한 트랜잭션: coupon_issues INSERT → coupons.issued_quantity +1 → issue_requests(COMPLETED) INSERT
 * 3) 영구 실패(회원 없음, 다른 요청으로 이미 발급됨, Redis/DB 불일치): FAILED 를 "먼저" 기록한 뒤 Redis 보상
 *    → 보상 후 FAILED 기록이 실패해서 재처리되면, 되돌린 재고로 발급되어 버리는 일을 막는 순서
 * 4) 일시 실패(DB 연결 등): 예외를 그대로 던진다 → 오프셋 커밋 없이 지수 백오프 재시도 → 소진되면 DLT
 */
@Component
public class CouponIssueEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueEventProcessor.class);
    static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    static final String PUBLISH_CANCELLED = "PUBLISH_CANCELLED";

    private final CouponIssueSupport support;
    private final CouponRepository couponRepository;
    private final IssueRequestRepository issueRequestRepository;
    private final PendingIssueRequestStore pendingStore;
    private final RedisCouponStock stock;
    private final CouponIssueConsumerMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CouponIssueEventProcessor(CouponIssueSupport support, CouponRepository couponRepository,
                                     IssueRequestRepository issueRequestRepository, PendingIssueRequestStore pendingStore,
                                     RedisCouponStock stock, CouponIssueConsumerMetrics metrics,
                                     PlatformTransactionManager transactionManager, Clock clock) {
        this.support = support;
        this.couponRepository = couponRepository;
        this.issueRequestRepository = issueRequestRepository;
        this.pendingStore = pendingStore;
        this.stock = stock;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void process(CouponIssueRequestedEvent event) {
        if (alreadyProcessed(event)) {
            skipDuplicate(event, "사전 확인");
            return;
        }
        if (stock.isRestored(event.eventId(), event.requestId())) {
            // 요청 경로가 Kafka 발행을 "실패"로 판단해 이미 재고를 되돌리고 503 을 준 요청인데, 메시지는 실제로 기록된 경우
            // → 발급하면 되돌린 재고가 한 번 더 나간다. 취소로 확정하고 보상은 하지 않는다 (이미 했으므로)
            recordCancelled(event);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                CouponIssue issue = support.saveIssue(event.eventId(), event.userId(), event.requestedAt());
                // 기간 검사는 Redis 판정 시각 기준 (컨슈머가 늦게 처리해도 정상 요청이 실패하지 않게)
                if (couponRepository.increaseIssuedQuantity(event.eventId(), event.requestedAt()) == 0) {
                    throw new IllegalStateException("Redis 와 DB 의 재고가 일치하지 않습니다. couponId=" + event.eventId());
                }
                issueRequestRepository.save(IssueRequest.completed(event.requestId(), event.eventId(), event.userId(),
                        issue.getId(), event.requestedAt(), clock.instant()));
            });
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.COUPON_ALREADY_ISSUED) {
                throw e;
            }
            // coupon_issues (coupon_id, user_id) 유니크 위반
            if (alreadyProcessed(event)) {
                skipDuplicate(event, "coupon_issues 유니크 제약");   // 같은 요청이 동시에 처리됨 → 정상
                return;
            }
            // 다른 요청으로 이미 받은 사람 (Redis Set 유실 후 재요청 등) → 재고만 되돌리고 Set 은 유지
            fail(event, ErrorCode.COUPON_ALREADY_ISSUED.name(), false);
            return;
        } catch (DataIntegrityViolationException e) {
            if (alreadyProcessed(event)) {
                skipDuplicate(event, "issue_requests 기본키");   // 같은 requestId 결과가 먼저 커밋됨 → 정상
                return;
            }
            fail(event, "DATA_INTEGRITY_VIOLATION", true);   // 예: 탈퇴한 회원 (FK 위반)
            return;
        } catch (IllegalStateException e) {
            fail(event, "STOCK_MISMATCH", true);
            return;
        }

        deletePending(event);
    }

    /**
     * DLT 컨슈머가 호출: 재시도를 모두 실패한 요청을 FAILED 로 확정하고 Redis 를 되돌린다.
     * 마지막 재시도의 DB 커밋이 실제로는 성공했을 수도 있으므로(타임아웃 등) 결과가 이미 있으면 아무것도 하지 않는다.
     * DB 가 아직 죽어 있으면 예외 → DLT 컨테이너가 계속 재시도한다.
     */
    public void failAfterRetriesExhausted(CouponIssueRequestedEvent event) {
        if (alreadyProcessed(event)) {
            log.info("DLT: 이미 결과가 있는 요청 — 건너뜀: requestId={}", event.requestId());
            return;
        }
        fail(event, RETRY_EXHAUSTED, true);
    }

    private boolean alreadyProcessed(CouponIssueRequestedEvent event) {
        return issueRequestRepository.existsById(event.requestId());
    }

    private void skipDuplicate(CouponIssueRequestedEvent event, String detectedBy) {
        metrics.skippedDuplicate();
        log.info("이미 처리한 발급 요청 — 건너뜀({}): requestId={}", detectedBy, event.requestId());
        deletePending(event);
    }

    private void fail(CouponIssueRequestedEvent event, String reason, boolean removeUser) {
        issueRequestRepository.save(IssueRequest.failed(event.requestId(), event.eventId(), event.userId(),
                reason, event.requestedAt(), clock.instant()));   // ① 결과를 먼저 확정
        log.warn("발급 요청 실패 처리: requestId={}, reason={}", event.requestId(), reason);
        try {
            // ② 그다음 Redis 보상 — requestId 로 멱등: 발행 실패 보상 / 컨슈머 실패 / DLT 가 겹쳐도 재고는 한 번만 복구
            boolean restored = stock.restore(event.eventId(), event.userId(), removeUser, event.requestId());
            if (!restored) {
                log.info("이미 복구된 요청 — 재고는 그대로: requestId={}", event.requestId());
            }
        } catch (RuntimeException e) {
            log.error("Redis 보상 실패: requestId={} — 대조 점검으로 확인 필요 (덜 발급되는 방향)", event.requestId(), e);
        }
        deletePending(event);
    }

    private void recordCancelled(CouponIssueRequestedEvent event) {
        issueRequestRepository.save(IssueRequest.failed(event.requestId(), event.eventId(), event.userId(),
                PUBLISH_CANCELLED, event.requestedAt(), clock.instant()));
        log.warn("발행 실패로 이미 취소(복구)된 요청 — 발급하지 않음: requestId={}", event.requestId());
        deletePending(event);
    }

    private void deletePending(CouponIssueRequestedEvent event) {
        try {
            pendingStore.delete(event.requestId());   // DB 에 결과가 있으므로 지우지 못해도 조회 결과는 맞다 (TTL 로 정리)
        } catch (RuntimeException e) {
            log.warn("PENDING 키 삭제 실패: requestId={}", event.requestId(), e);
        }
    }
}

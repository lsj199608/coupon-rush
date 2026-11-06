package com.couponrush.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 상태 전이는 전부 "PENDING 일 때만" 조건부 UPDATE. 반환값 = 바뀐 행 수 (0 이면 이미 끝난 요청 → 무시).
 * <ul>
 *   <li>결과가 두 번 와도 (재전달) → 두 번째는 0행</li>
 *   <li>TIMEOUT 처리 뒤에 결과가 와도 → 0행 (늦은 결과는 버린다. 관리자는 이미 실패 안내를 봤다)</li>
 *   <li>결과와 TIMEOUT 정리가 동시에 → 행 잠금 후 먼저 커밋한 쪽만 반영</li>
 * </ul>
 */
public interface LlmRequestRepository extends JpaRepository<LlmRequest, UUID> {

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE llm_requests
               SET status = 'COMPLETED', result = CAST(:result AS jsonb), model = :model,
                   input_tokens = :inputTokens, output_tokens = :outputTokens, cached = :cached, completed_at = :completedAt
             WHERE request_id = :requestId AND status = 'PENDING'
            """, nativeQuery = true)
    int complete(@Param("requestId") UUID requestId, @Param("result") String resultJson, @Param("model") String model,
                 @Param("inputTokens") int inputTokens, @Param("outputTokens") int outputTokens,
                 @Param("cached") boolean cached, @Param("completedAt") Instant completedAt);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE llm_requests
               SET status = 'FAILED', failure_reason = :reason, model = :model,
                   input_tokens = :inputTokens, output_tokens = :outputTokens, completed_at = :completedAt
             WHERE request_id = :requestId AND status = 'PENDING'
            """, nativeQuery = true)
    int fail(@Param("requestId") UUID requestId, @Param("reason") String reason, @Param("model") String model,
             @Param("inputTokens") int inputTokens, @Param("outputTokens") int outputTokens,
             @Param("completedAt") Instant completedAt);

    /** 이 요청이 마감을 넘겼으면 TIMEOUT (조회 시점에 바로 확정 — 스케줄러 주기를 기다리지 않게) */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE llm_requests SET status = 'FAILED', failure_reason = 'TIMEOUT', completed_at = :now
             WHERE request_id = :requestId AND status = 'PENDING' AND deadline_at <= :now
            """, nativeQuery = true)
    int timeoutIfExpired(@Param("requestId") UUID requestId, @Param("now") Instant now);

    /** 마감 지난 PENDING 전부 TIMEOUT (스케줄러). 부분 인덱스 idx_llm_requests_pending_deadline 사용 */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE llm_requests SET status = 'FAILED', failure_reason = 'TIMEOUT', completed_at = :now
             WHERE status = 'PENDING' AND deadline_at <= :now
            """, nativeQuery = true)
    int timeoutAllExpired(@Param("now") Instant now);
}

import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchIssueRequest, requestIssue } from '../api/coupons';
import { errorCodeOf } from '../api/errors';
import { queryKeys } from '../api/queryKeys';

/** 폴링 설정. 테스트에서 짧게 바꿀 수 있게 객체로 둔다 */
export const ISSUE_POLLING = {
  /** PENDING 동안 GET /api/issue-requests/{id} 를 부르는 간격 */
  intervalMs: 1_000,
  /** 이 시간 동안 PENDING 이면 폴링을 멈추고 지연 안내 (요청은 서버에서 계속 처리될 수 있다) */
  timeoutMs: 30_000,
};

/**
 * 발급 흐름 상태
 * - idle:       아직 누르지 않음
 * - submitting: POST /issue 응답 대기 (Redis 판정 + Kafka ack)
 * - pending:    202 를 받고 결과 폴링 중 (DB 저장 대기)
 * - completed:  발급 완료
 * - failed:     접수됐지만 DB 저장 실패 (FAILED + failureReason) — Redis 는 보상됐으므로 다시 시도 가능
 * - rejected:   즉시 거절 (409 소진/이미 발급, 400 기간 아님, 503 등 — errorCode)
 * - timeout:    정해진 시간 안에 결과가 안 나옴
 * - lost:       결과 조회 자체가 실패 (네트워크, PENDING 키 만료 등)
 */
export type IssuePhase =
  | { phase: 'idle' }
  | { phase: 'submitting' }
  | { phase: 'pending'; requestId: string }
  | { phase: 'completed' }
  | { phase: 'failed'; reason?: string }
  | { phase: 'rejected'; error: unknown; code?: string }
  | { phase: 'timeout' }
  | { phase: 'lost'; error: unknown };

export function useIssueCoupon(couponId: number) {
  const queryClient = useQueryClient();
  const [requestId, setRequestId] = useState<string | null>(null);
  const [timedOut, setTimedOut] = useState(false);
  /** 렌더링 전에 연달아 들어오는 클릭까지 막는다 (isPending 은 다음 렌더에서야 바뀐다) */
  const inFlight = useRef(false);

  const mutation = useMutation({
    mutationFn: () => requestIssue(couponId),
    onSuccess: (result) => {
      if (result.kind === 'accepted') {
        setRequestId(result.requestId);
      }
    },
    onSettled: () => {
      // 거절이든 접수든 남은 수량이 바뀌었을 수 있다
      void queryClient.invalidateQueries({ queryKey: queryKeys.event(couponId) });
    },
  });

  const poll = useQuery({
    queryKey: queryKeys.issueRequest(requestId ?? ''),
    queryFn: () => fetchIssueRequest(requestId!),
    enabled: requestId !== null && !timedOut,
    retry: 2,
    // PENDING(또는 아직 첫 응답 전)일 때만 반복, COMPLETED/FAILED 면 멈춘다
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === undefined || status === 'PENDING' ? ISSUE_POLLING.intervalMs : false;
    },
    staleTime: 0,
    gcTime: 0,
  });

  const pollStatus = poll.data?.status;
  const finished = pollStatus === 'COMPLETED' || pollStatus === 'FAILED';

  // 타임아웃: 202 를 받은 순간부터 잰다. 의존성을 pollStatus 로 두면 첫 응답(undefined → PENDING) 때 타이머가 다시 시작돼
  // 실제 대기가 30초를 넘으므로 "끝났는지"만 본다
  useEffect(() => {
    if (requestId === null || finished) return;
    const timer = setTimeout(() => setTimedOut(true), ISSUE_POLLING.timeoutMs);
    return () => clearTimeout(timer);
  }, [requestId, finished]);

  // 발급이 끝나면 목록·상세·내 쿠폰을 새로 가져오게 한다
  const issuedSynchronously = mutation.data?.kind === 'issued';
  useEffect(() => {
    if (pollStatus === 'COMPLETED' || issuedSynchronously) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.events });
      void queryClient.invalidateQueries({ queryKey: queryKeys.myCoupons });
    }
    if (pollStatus === 'COMPLETED' || pollStatus === 'FAILED' || issuedSynchronously || mutation.isError) {
      inFlight.current = false;
    }
  }, [pollStatus, issuedSynchronously, mutation.isError, queryClient]);

  const state: IssuePhase = (() => {
    if (mutation.isPending) return { phase: 'submitting' };
    if (mutation.isError) return { phase: 'rejected', error: mutation.error, code: errorCodeOf(mutation.error) };
    if (issuedSynchronously) return { phase: 'completed' };
    if (requestId === null) return { phase: 'idle' };
    if (pollStatus === 'COMPLETED') return { phase: 'completed' };
    if (pollStatus === 'FAILED') return { phase: 'failed', reason: poll.data?.failureReason };
    if (timedOut) return { phase: 'timeout' };
    if (poll.isError) return { phase: 'lost', error: poll.error };
    return { phase: 'pending', requestId };
  })();

  const issue = useCallback(() => {
    if (inFlight.current) return;
    inFlight.current = true;
    setRequestId(null);
    setTimedOut(false);
    mutation.mutate();
  }, [mutation]);

  /** 다시 시도할 수 있는 실패 후 처음 상태로 */
  const reset = useCallback(() => {
    inFlight.current = false;
    setRequestId(null);
    setTimedOut(false);
    mutation.reset();
  }, [mutation]);

  return { state, issue, reset };
}

import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { errorCodeOf } from '../api/errors';
import { fetchLlmRequest, requestEventCopy, type EventCopyCandidate, type EventCopyInput } from '../api/llm';
import { queryKeys } from '../api/queryKeys';

/** 폴링 설정 (테스트에서 짧게 바꿀 수 있게 객체로). 서버 마감은 60초 — 화면은 여유를 두고 75초에 멈춘다 */
export const EVENT_COPY_POLLING = {
  intervalMs: 2_000,
  timeoutMs: 75_000,
};

/**
 * 문구 생성 상태 (useIssueCoupon 과 같은 구조 — 서버 데이터(mutation + query)에서 계산한다)
 * - idle / submitting(접수 대기) / pending(202 후 폴링)
 * - completed: 후보 도착 (cached = 같은 입력의 이전 결과)
 * - failed:    처리됐지만 실패 (사유, 다시 시도 가능 여부는 서버가 알려 준다)
 * - rejected:  접수 단계 거절 (429 한도·예산, 503, 400 등 — errorCode)
 * - timeout:   화면 쪽 대기 한도 초과 (서버도 곧 TIMEOUT 으로 끝낸다)
 * - lost:      결과 조회 자체 실패
 */
export type EventCopyPhase =
  | { phase: 'idle' }
  | { phase: 'submitting' }
  | { phase: 'pending' }
  | { phase: 'completed'; candidates: EventCopyCandidate[]; cached: boolean }
  | { phase: 'failed'; reason: string | null; retryable: boolean }
  | { phase: 'rejected'; error: unknown; code?: string }
  | { phase: 'timeout' }
  | { phase: 'lost'; error: unknown };

export function useEventCopy() {
  const [requestId, setRequestId] = useState<string | null>(null);
  const [timedOut, setTimedOut] = useState(false);
  /** 렌더링 전 연타 방지 (비용이 드는 요청이라 특히 중요) */
  const inFlight = useRef(false);

  const mutation = useMutation({
    mutationFn: requestEventCopy,
    onSuccess: (accepted) => setRequestId(accepted.requestId),
  });

  const poll = useQuery({
    queryKey: queryKeys.llmRequest(requestId ?? ''),
    queryFn: () => fetchLlmRequest(requestId!),
    enabled: requestId !== null && !timedOut,
    retry: 2,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === undefined || status === 'PENDING' ? EVENT_COPY_POLLING.intervalMs : false;
    },
    staleTime: 0,
    gcTime: 0,
  });

  const pollStatus = poll.data?.status;
  const finished = pollStatus === 'COMPLETED' || pollStatus === 'FAILED';

  // 타임아웃은 202 를 받은 순간부터 (의존성은 "끝났는지"만 — useIssueCoupon 에서 겪은 타이머 재시작 버그 방지)
  useEffect(() => {
    if (requestId === null || finished) return;
    const timer = setTimeout(() => setTimedOut(true), EVENT_COPY_POLLING.timeoutMs);
    return () => clearTimeout(timer);
  }, [requestId, finished]);

  useEffect(() => {
    if (finished || timedOut || mutation.isError || poll.isError) {
      inFlight.current = false;
    }
  }, [finished, timedOut, mutation.isError, poll.isError]);

  const state: EventCopyPhase = (() => {
    if (mutation.isPending) return { phase: 'submitting' };
    if (mutation.isError) return { phase: 'rejected', error: mutation.error, code: errorCodeOf(mutation.error) };
    if (requestId === null) return { phase: 'idle' };
    if (pollStatus === 'COMPLETED') {
      return { phase: 'completed', candidates: poll.data!.candidates, cached: poll.data!.cached };
    }
    if (pollStatus === 'FAILED') {
      return { phase: 'failed', reason: poll.data!.failureReason, retryable: poll.data!.retryable === true };
    }
    if (timedOut) return { phase: 'timeout' };
    if (poll.isError) return { phase: 'lost', error: poll.error };
    return { phase: 'pending' };
  })();

  const generate = useCallback(
    (input: EventCopyInput) => {
      if (inFlight.current) return;
      inFlight.current = true;
      setRequestId(null);
      setTimedOut(false);
      mutation.mutate(input);
    },
    [mutation],
  );

  const reset = useCallback(() => {
    inFlight.current = false;
    setRequestId(null);
    setTimedOut(false);
    mutation.reset();
  }, [mutation]);

  const busy = state.phase === 'submitting' || state.phase === 'pending';
  return { state, generate, reset, busy };
}

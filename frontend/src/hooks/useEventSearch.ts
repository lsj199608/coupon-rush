import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../api/queryKeys';
import { fetchEventSearch, startEventSearch, type EventSearchResult } from '../api/search';

/** 폴링 설정 (테스트에서 짧게). 서버 마감 15초가 지나면 서버가 키워드 검색으로 끝내므로, 화면은 여유를 두고 25초에 멈춘다 */
export const EVENT_SEARCH_POLLING = {
  intervalMs: 1_000,
  timeoutMs: 25_000,
  /** 같은 질문으로 돌아왔을 때(상세 → 뒤로) 다시 접수하지 않고 결과를 재사용하는 시간 */
  reuseMs: 5 * 60_000,
};

/**
 * 검색 상태 (서버 데이터에서 계산 — useState 에 결과를 복사하지 않는다)
 * - idle:      질문 없음 (전체 목록을 보여 준다)
 * - searching: 접수 중 또는 AI 가 조건을 만드는 중 (PENDING 폴링)
 * - done:      COMPLETED(AI 조건) 또는 FALLBACK(키워드 검색) — 둘 다 결과가 있는 정상 상태
 * - timeout:   화면 대기 한도 초과 (서버가 응답하지 않을 때만)
 * - error:     접수·조회 자체 실패 (400 입력 오류, 네트워크 등)
 */
export type EventSearchPhase =
  | { phase: 'idle' }
  | { phase: 'searching' }
  | { phase: 'done'; result: EventSearchResult }
  | { phase: 'timeout' }
  | { phase: 'error'; error: unknown };

/**
 * @param query 검색어 (URL ?q= 에서 온다). null/빈 문자열이면 검색하지 않는다.
 *
 * 접수(POST)를 useQuery 로 둔다: 질문별 키로 결과가 캐시돼, 상세에 갔다가 뒤로 오면 다시 접수(한도·비용)하지 않는다.
 * 검색은 데이터를 바꾸지 않는 "조회"라 mutation 이 아니고, 재시도는 끈다 (접수할 때마다 한도를 쓴다).
 */
export function useEventSearch(query: string | null) {
  const enabled = query !== null && query.trim() !== '';
  const [timedOutRequest, setTimedOutRequest] = useState<string | null>(null);

  const start = useQuery({
    queryKey: queryKeys.eventSearch(query ?? ''),
    queryFn: () => startEventSearch(query!),
    enabled,
    retry: false,
    staleTime: EVENT_SEARCH_POLLING.reuseMs,
  });

  const pendingRequestId = start.data?.status === 'PENDING' ? start.data.requestId : null;
  const timedOut = pendingRequestId !== null && timedOutRequest === pendingRequestId;

  const poll = useQuery({
    queryKey: queryKeys.eventSearchRequest(pendingRequestId ?? ''),
    queryFn: () => fetchEventSearch(pendingRequestId!),
    enabled: pendingRequestId !== null && !timedOut,
    retry: 2,
    refetchInterval: (q) => {
      const status = q.state.data?.status;
      return status === undefined || status === 'PENDING' ? EVENT_SEARCH_POLLING.intervalMs : false;
    },
    staleTime: EVENT_SEARCH_POLLING.reuseMs,
  });

  const finished = start.data !== undefined && (start.data.status !== 'PENDING' || (poll.data !== undefined && poll.data.status !== 'PENDING'));

  // 타이머는 요청마다 한 번 (의존성은 요청 ID와 "끝났는지"만 — 폴링 응답마다 다시 시작되지 않게)
  useEffect(() => {
    if (pendingRequestId === null || finished) return;
    const timer = setTimeout(() => setTimedOutRequest(pendingRequestId), EVENT_SEARCH_POLLING.timeoutMs);
    return () => clearTimeout(timer);
  }, [pendingRequestId, finished]);

  const state: EventSearchPhase = (() => {
    if (!enabled) return { phase: 'idle' };
    if (start.isError) return { phase: 'error', error: start.error };
    if (start.data === undefined) return { phase: 'searching' };
    if (start.data.status !== 'PENDING') return { phase: 'done', result: start.data };
    if (poll.data !== undefined && poll.data.status !== 'PENDING') return { phase: 'done', result: poll.data };
    if (timedOut) return { phase: 'timeout' };
    if (poll.isError) return { phase: 'error', error: poll.error };
    return { phase: 'searching' };
  })();

  /** 같은 질문으로 다시 검색 (오류·타임아웃 뒤) — 캐시를 버리고 새로 접수한다 */
  const retry = () => {
    setTimedOutRequest(null);
    void start.refetch();
  };

  return { state, retry };
}

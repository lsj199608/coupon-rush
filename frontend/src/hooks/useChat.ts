import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { askChat, createChatSession, fetchChatSession, fetchChatSessions, type ChatTurn } from '../api/chat';
import { errorCodeOf } from '../api/errors';
import { queryKeys } from '../api/queryKeys';

/** 폴링 설정 (테스트에서 짧게). 서버는 마감(60초)이 지나면 조회 때 TIMEOUT 으로 끝내므로, 화면은 마감 + 여유까지만 기다린다 */
export const CHAT_POLLING = {
  intervalMs: 1_000,
  graceMs: 15_000,
};

const storageKey = (userId: number) => `coupon-rush:chat-session:${userId}`;

function readStored(userId: number): number | null {
  try {
    const value = sessionStorage.getItem(storageKey(userId));
    return value ? Number(value) : null;
  } catch {
    return null;
  }
}

function writeStored(userId: number, sessionId: number | null) {
  try {
    if (sessionId === null) sessionStorage.removeItem(storageKey(userId));
    else sessionStorage.setItem(storageKey(userId), String(sessionId));
  } catch {
    // 저장소를 못 쓰면(사생활 보호 모드 등) 새로고침 때 최근 대화방을 서버 목록에서 다시 고른다
  }
}

/** 답을 기다리는 중인가 — 마감 + 여유가 지나면 더 기다리지 않는다 (서버가 응답하지 않을 때만 해당) */
export function isWaiting(turn: ChatTurn | undefined, now: number): boolean {
  return turn?.status === 'PENDING' && now < Date.parse(turn.deadlineAt) + CHAT_POLLING.graceMs;
}

/**
 * 챗봇 대화 상태. 대화 내용(턴)은 서버 데이터라 TanStack Query 로만 들고 있고, 여기 state 는 "어느 대화방을 보고 있나"뿐이다.
 * <ul>
 *   <li>대화방: 이 탭에서 보던 방(sessionStorage, 회원별 키) → 없으면 서버 목록의 최근 방 → 그것도 없으면 첫 질문 때 만든다</li>
 *   <li>질문: POST 202 → 대화방 조회를 다시 해서 PENDING 턴을 받고, 마지막 턴이 PENDING 인 동안만 1초마다 다시 조회</li>
 *   <li>[새 대화]: 방을 비워 두고 다음 질문 때 새로 만든다 (빈 방을 미리 만들지 않음)</li>
 * </ul>
 *
 * @param enabled 창이 열려 있을 때만 조회한다 (닫혀 있으면 폴링도 멈춘다)
 */
export function useChat(userId: number, enabled: boolean) {
  const queryClient = useQueryClient();
  const [sessionId, setSessionIdState] = useState<number | null>(() => readStored(userId));
  /** [새 대화]를 눌렀다 — 서버 목록의 최근 방으로 돌아가지 않는다 */
  const [fresh, setFresh] = useState(false);
  const inFlight = useRef(false);

  const setSessionId = useCallback(
    (id: number | null) => {
      setSessionIdState(id);
      writeStored(userId, id);
    },
    [userId],
  );

  const sessions = useQuery({
    queryKey: queryKeys.chatSessions,
    queryFn: fetchChatSessions,
    enabled: enabled && sessionId === null && !fresh,
  });

  // 보던 방이 없으면 최근 방을 고른다 (선택은 화면 상태라 서버 데이터를 복사하는 것이 아니다)
  useEffect(() => {
    if (sessionId === null && !fresh && sessions.data && sessions.data.length > 0) {
      setSessionId(sessions.data[0].sessionId);
    }
  }, [sessionId, fresh, sessions.data, setSessionId]);

  const session = useQuery({
    queryKey: queryKeys.chatSession(sessionId ?? 0),
    queryFn: () => fetchChatSession(sessionId!),
    enabled: enabled && sessionId !== null,
    retry: false,
    refetchInterval: (q) => {
      const turns = q.state.data?.turns;
      return isWaiting(turns?.[turns.length - 1], Date.now()) ? CHAT_POLLING.intervalMs : false;
    },
  });

  // 저장해 둔 방이 없어졌거나 남의 방이면(다른 회원으로 다시 로그인 등) 잊고 최근 방을 다시 고른다
  useEffect(() => {
    if (session.isError && errorCodeOf(session.error) === 'CHAT_SESSION_NOT_FOUND') {
      setSessionId(null);
    }
  }, [session.isError, session.error, setSessionId]);

  const ask = useMutation({
    mutationFn: async (message: string) => {
      const id = sessionId ?? (await createChatSession()).sessionId;
      if (id !== sessionId) {
        setSessionId(id);
        setFresh(false);
      }
      await askChat(id, message);
      return id;
    },
    onSuccess: (id) => queryClient.invalidateQueries({ queryKey: queryKeys.chatSession(id) }),
    onSettled: () => {
      inFlight.current = false;
      void queryClient.invalidateQueries({ queryKey: queryKeys.chatSessions });
    },
  });

  const turns = session.data?.turns ?? [];
  const waiting = isWaiting(turns[turns.length - 1], Date.now());

  const send = useCallback(
    (message: string) => {
      const trimmed = message.trim();
      // 렌더 전 연타까지 막는다 (isPending 은 다음 렌더에서야 바뀐다)
      if (trimmed === '' || inFlight.current || waiting) return false;
      inFlight.current = true;
      ask.reset();
      ask.mutate(trimmed);
      return true;
    },
    [ask, waiting],
  );

  const newChat = useCallback(() => {
    setSessionId(null);
    setFresh(true);
    ask.reset();
  }, [ask, setSessionId]);

  return {
    turns,
    /** 처음 대화방을 불러오는 중 */
    loading: (session.isPending && sessionId !== null && enabled) || (sessions.isPending && sessionId === null && !fresh && enabled),
    loadError: session.isError && errorCodeOf(session.error) !== 'CHAT_SESSION_NOT_FOUND' ? session.error : null,
    reload: () => session.refetch(),
    send,
    sending: ask.isPending,
    sendError: ask.error,
    waiting,
    newChat,
  };
}

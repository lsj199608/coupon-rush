import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { queryKeys } from '../api/queryKeys';
import {
  connectWebChat,
  contentLength,
  fetchWebChatHistory,
  WEB_CHAT_CONTENT_MAX,
  type WebChatConnection,
  type WebChatHistoryPage,
  type WebChatMessage,
  type WebChatRejection,
} from '../api/webChat';

/** 실시간으로 받은 메시지를 화면에 들고 있는 최대 수 (탭을 오래 열어 둬도 메모리가 계속 늘지 않게) */
const LIVE_MAX = 500;

export type WebChatStatus = 'connecting' | 'connected' | 'reconnecting';

/**
 * 지난 대화(페이지: 최신 페이지가 0번, 페이지 안은 오래된 것부터) + 실시간 메시지를 한 줄로.
 * 같은 메시지가 양쪽에 있을 수 있다(구독을 먼저 하고 기록을 읽으므로) → messageId 로 한 번만, 기록 쪽 위치로.
 */
export function mergeMessages(pages: WebChatHistoryPage[], live: WebChatMessage[]): WebChatMessage[] {
  const history = [...pages].reverse().flatMap((page) => page.messages);
  const seen = new Set<string>();
  const merged: WebChatMessage[] = [];
  for (const message of [...history, ...live]) {
    if (seen.has(message.messageId)) continue;
    seen.add(message.messageId);
    merged.push(message);
  }
  return merged;
}

/**
 * 이벤트 채팅방 상태 (7단계 D4).
 * <ul>
 *   <li>입장 순서 = **구독 먼저 → 지난 대화 조회**. 반대로 하면 조회와 구독 사이에 온 메시지를 놓친다</li>
 *   <li>다시 연결되면 지난 대화를 다시 읽는다 — 서버 간 전달(Redis pub/sub)은 끊긴 동안의 메시지를 남겨 두지 않지만 DB 에는 있다</li>
 *   <li>지난 대화는 서버 데이터라 TanStack Query(무한 조회, beforeId 커서), 실시간 메시지는 소켓에서만 오므로 state</li>
 * </ul>
 * 회원(로그인 여부)이 바뀌면 연결을 새로 맺는다 — 연결 하나는 한 회원(티켓)으로 정해진다.
 */
export function useWebChat(eventId: number, userId: number | null) {
  const [status, setStatus] = useState<WebChatStatus>('connecting');
  const [canSend, setCanSend] = useState(false);
  const [live, setLive] = useState<WebChatMessage[]>([]);
  const [rejection, setRejection] = useState<WebChatRejection | null>(null);
  /** 연결된 횟수 — 0 이면 아직 구독 전이라 기록을 읽지 않는다, 2 이상이면 다시 연결된 것 */
  const [connectCount, setConnectCount] = useState(0);
  const connection = useRef<WebChatConnection | null>(null);

  useEffect(() => {
    setStatus('connecting');
    setCanSend(false);
    const conn = connectWebChat(eventId, userId !== null, {
      onConnected: (withTicket) => {
        setStatus('connected');
        setCanSend(withTicket);
        setConnectCount((count) => count + 1);
      },
      onDisconnected: () => {
        setStatus('reconnecting');
        setCanSend(false);
      },
      onMessage: (message) => setLive((prev) => [...prev, message].slice(-LIVE_MAX)),
      onRejected: setRejection,
    });
    connection.current = conn;
    return () => {
      conn.close();
      connection.current = null;
    };
  }, [eventId, userId]);

  const history = useInfiniteQuery({
    queryKey: queryKeys.webChatHistory(eventId),
    queryFn: ({ pageParam }) => fetchWebChatHistory(eventId, pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => last.nextBeforeId ?? undefined,
    enabled: connectCount > 0,
    // 다시 읽는 때는 "다시 연결됐을 때"로 정한다 (창 포커스마다 다시 읽지 않음)
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });

  const { refetch } = history;
  useEffect(() => {
    if (connectCount > 1) void refetch();
  }, [connectCount, refetch]);

  const messages = useMemo(() => mergeMessages(history.data?.pages ?? [], live), [history.data, live]);

  /** 보냈으면 true (입력칸을 비운다). 내 메시지는 방 구독으로 돌아와 화면에 나온다 */
  const send = useCallback((text: string): boolean => {
    const content = text.trim();
    if (content === '' || contentLength(content) > WEB_CHAT_CONTENT_MAX) return false;
    setRejection(null);
    return connection.current?.send(content) ?? false;
  }, []);

  return {
    status,
    canSend,
    messages,
    send,
    rejection,
    clearRejection: () => setRejection(null),
    /** 첫 지난 대화를 읽는 중 */
    historyLoading: connectCount > 0 && history.isPending,
    historyError: history.isError ? history.error : null,
    reloadHistory: () => refetch(),
    hasOlder: history.hasNextPage,
    loadingOlder: history.isFetchingNextPage,
    loadOlder: () => history.fetchNextPage(),
  };
}

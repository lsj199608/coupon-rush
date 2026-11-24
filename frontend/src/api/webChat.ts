import axios from 'axios';
import { Client, ReconnectionTimeMode, type IMessage } from '@stomp/stompjs';
import { apiClient } from './client';

/**
 * 이벤트 채팅방 (7단계 D, 사람 ↔ 사람). 두 서비스를 쓴다:
 * <ul>
 *   <li>coupon-api(8080) POST /api/web-chat/tickets — 로그인 회원의 1회용 연결 티켓(30초)</li>
 *   <li>web-chat-service(8083) ws /ws/web-chat (STOMP) + GET /api/web-chat/rooms/{id}/messages(지난 대화, 공개)</li>
 * </ul>
 * 브라우저에게는 전부 같은 출처(5173) — Vite 프록시가 경로로 나눈다(vite.config.ts).
 */

/** 방 구독으로 받는 메시지. 보낸 사람·시각·ID 는 서버가 채운다 */
export interface WebChatMessage {
  messageId: string;
  eventId: number;
  senderId: number;
  senderNickname: string;
  /** 글자로만 그린다 (HTML 로 해석하지 않음) */
  content: string;
  sentAt: string;
}

/** 저장된 메시지 = 실시간 메시지 + 저장 순서 id (이전 페이지 커서) */
export interface WebChatHistoryMessage extends WebChatMessage {
  id: number;
}

export interface WebChatHistoryPage {
  /** 오래된 것부터 */
  messages: WebChatHistoryMessage[];
  /** 더 오래된 메시지가 있으면 다음 요청의 beforeId */
  nextBeforeId: number | null;
}

export interface WebChatTicket {
  ticket: string;
  expiresAt: string;
}

/** 내 메시지 거절 (/user/queue/errors, 연결 유지) */
export interface WebChatRejection {
  code: string;
  message: string;
}

/** 서버 본문 상한 (WebChatMessage.CONTENT_MAX) — 글자(코드 포인트) 기준 */
export const WEB_CHAT_CONTENT_MAX = 500;

/** 서버와 같은 기준으로 센다 — 이모지 하나를 두 글자로 세지 않게 (textarea maxLength 는 UTF-16 단위라 쓰지 않는다) */
export function contentLength(text: string): number {
  return [...text.trim()].length;
}

export const WEB_CHAT_CONNECTION = {
  /** 다시 연결 대기: 1초부터 2배씩, 최대 30초 (서버가 잠깐 내려가도 모든 탭이 동시에 두드리지 않게) */
  reconnectDelayMs: 1_000,
  maxReconnectDelayMs: 30_000,
  /** 서버 하트비트(10초)와 같게 — 끊긴 연결을 늦지 않게 알아챈다 */
  heartbeatMs: 10_000,
};

export async function issueWebChatTicket(): Promise<WebChatTicket> {
  const response = await apiClient.post<WebChatTicket>('/api/web-chat/tickets');
  return response.data;
}

/**
 * 지난 대화. apiClient 를 쓰지 않는다 — 공개 API 라 로그인 토큰(Bearer)을 채팅 서버에 보낼 이유가 없다
 * (채팅 서버는 JWT 를 모르는 서비스. 비밀값은 필요한 곳에만).
 */
export async function fetchWebChatHistory(eventId: number, beforeId?: number): Promise<WebChatHistoryPage> {
  const response = await axios.get<WebChatHistoryPage>(`/api/web-chat/rooms/${eventId}/messages`, {
    params: beforeId === undefined ? undefined : { beforeId },
  });
  return response.data;
}

export interface WebChatHandlers {
  /** 연결(다시 연결 포함)되고 구독까지 보낸 뒤. canSend = 로그인 회원으로 연결됐는가 */
  onConnected: (canSend: boolean) => void;
  /** 연결이 끊김 — 클라이언트가 알아서 다시 연결한다 */
  onDisconnected: () => void;
  onMessage: (message: WebChatMessage) => void;
  onRejected: (rejection: WebChatRejection) => void;
}

export interface WebChatConnection {
  /** 연결돼 있지 않으면 보내지 않고 false */
  send: (content: string) => boolean;
  close: () => void;
}

function webSocketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws/web-chat`;
}

function parse<T>(message: IMessage): T | null {
  try {
    return JSON.parse(message.body) as T;
  } catch {
    return null;
  }
}

function isMessage(value: WebChatMessage | null): value is WebChatMessage {
  return value !== null && typeof value.messageId === 'string' && typeof value.content === 'string';
}

/**
 * 이벤트 방에 연결한다. 끊기면 자동으로 다시 연결하고, **연결할 때마다 새 티켓**을 받는다(티켓은 1회용·30초).
 * <ul>
 *   <li>loggedIn=false → 티켓 없이 연결(읽기만)</li>
 *   <li>티켓을 못 받으면(coupon-api 장애·로그인 만료) 읽기만으로 연결 — 대화를 보는 것까지 막지 않는다. 다음 재연결 때 다시 받아 본다</li>
 *   <li>티켓이 거절되면(만료 등) 서버가 ERROR + 연결 종료 → 자동 재연결이 새 티켓으로</li>
 * </ul>
 */
export function connectWebChat(eventId: number, loggedIn: boolean, handlers: WebChatHandlers): WebChatConnection {
  let withTicket = false;

  const client = new Client({
    brokerURL: webSocketUrl(),
    reconnectDelay: WEB_CHAT_CONNECTION.reconnectDelayMs,
    maxReconnectDelay: WEB_CHAT_CONNECTION.maxReconnectDelayMs,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: WEB_CHAT_CONNECTION.heartbeatMs,
    heartbeatOutgoing: WEB_CHAT_CONNECTION.heartbeatMs,
  });

  // 여기서 예외가 나면 클라이언트가 연결을 멈춘다(재연결도 없음) → 모든 실패를 안에서 처리한다
  client.beforeConnect = async () => {
    withTicket = false;
    client.connectHeaders = {};
    if (!loggedIn) return;
    try {
      const { ticket } = await issueWebChatTicket();
      client.connectHeaders = { ticket };
      withTicket = true;
    } catch {
      // 읽기만으로 연결
    }
  };

  client.onConnect = () => {
    client.subscribe(`/topic/events/${eventId}`, (frame) => {
      const message = parse<WebChatMessage>(frame);
      if (isMessage(message)) handlers.onMessage(message);
    });
    if (withTicket) {
      client.subscribe('/user/queue/errors', (frame) => {
        const rejection = parse<WebChatRejection>(frame);
        if (rejection?.code) handlers.onRejected(rejection);
      });
    }
    handlers.onConnected(withTicket);
  };

  let closed = false;
  client.onWebSocketClose = () => {
    if (!closed) handlers.onDisconnected();
  };

  client.activate();

  return {
    send: (content) => {
      if (closed || !client.connected || !withTicket) return false;
      client.publish({ destination: `/app/events/${eventId}/chat`, body: JSON.stringify({ content }) });
      return true;
    },
    close: () => {
      closed = true;
      void client.deactivate();
    },
  };
}

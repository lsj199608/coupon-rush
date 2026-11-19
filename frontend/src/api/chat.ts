import { apiClient } from './client';

/**
 * 챗봇 대화 (coupon-api /api/chat/**, 로그인 필요). 답은 llm-service 가 만들고, 화면은 대화방을 다시 조회해 기다린다.
 * 누가 묻는지는 로그인 토큰으로만 정해진다 (질문 문장에 회원 번호를 써도 바뀌지 않는다).
 */
export type ChatTurnStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

/** 질문 하나 + 그 답. 답·실패는 서버가 정한다 */
export interface ChatTurn {
  turnId: number;
  question: string;
  status: ChatTurnStatus;
  /** COMPLETED 일 때만 */
  answer: string | null;
  /** FAILED 일 때만 (백엔드 LlmFailureReason) */
  failureReason: string | null;
  /** FAILED 일 때 같은 질문을 다시 보낼 의미가 있는가 */
  retryable: boolean | null;
  createdAt: string;
  /** 이 시각까지 답이 없으면 서버가 TIMEOUT 으로 끝낸다 */
  deadlineAt: string;
  answeredAt: string | null;
}

export interface ChatSession {
  sessionId: number;
  createdAt: string;
  lastMessageAt: string;
  /** 오래된 것부터, 최근 50개까지 */
  turns: ChatTurn[];
}

export interface ChatSessionSummary {
  sessionId: number;
  /** 첫 질문 앞부분 (질문이 없으면 null) */
  title: string | null;
  createdAt: string;
  lastMessageAt: string;
}

/** 서버의 질문 길이 상한 (계약 ChatInput.MESSAGE_MAX) */
export const CHAT_MESSAGE_MAX = 500;

export async function createChatSession(): Promise<ChatSession> {
  const response = await apiClient.post<ChatSession>('/api/chat/sessions');
  return response.data;
}

export async function fetchChatSessions(): Promise<ChatSessionSummary[]> {
  const response = await apiClient.get<ChatSessionSummary[]>('/api/chat/sessions');
  return response.data;
}

export async function fetchChatSession(sessionId: number): Promise<ChatSession> {
  const response = await apiClient.get<ChatSession>(`/api/chat/sessions/${sessionId}`);
  return response.data;
}

/** 202 PENDING 턴. 답은 fetchChatSession 으로 다시 조회해 기다린다 */
export async function askChat(sessionId: number, message: string): Promise<ChatTurn> {
  const response = await apiClient.post<ChatTurn>(`/api/chat/sessions/${sessionId}/turns`, { message });
  return response.data;
}

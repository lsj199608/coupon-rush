import { apiClient } from './client';

/**
 * 챗봇 대화 (coupon-api /api/chatbot/**, 로그인 필요). 답은 llm-service 가 만들고, 화면은 대화방을 다시 조회해 기다린다.
 * 누가 묻는지는 로그인 토큰으로만 정해진다 (질문 문장에 회원 번호를 써도 바뀌지 않는다).
 */
export type ChatbotTurnStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

/** 질문 하나 + 그 답. 답·실패는 서버가 정한다 */
export interface ChatbotTurn {
  turnId: number;
  question: string;
  status: ChatbotTurnStatus;
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

export interface ChatbotSession {
  sessionId: number;
  createdAt: string;
  lastMessageAt: string;
  /** 오래된 것부터, 최근 50개까지 */
  turns: ChatbotTurn[];
}

export interface ChatbotSessionSummary {
  sessionId: number;
  /** 첫 질문 앞부분 (질문이 없으면 null) */
  title: string | null;
  createdAt: string;
  lastMessageAt: string;
}

/** 서버의 질문 길이 상한 (계약 ChatbotInput.MESSAGE_MAX) */
export const CHATBOT_MESSAGE_MAX = 500;

export async function createChatbotSession(): Promise<ChatbotSession> {
  const response = await apiClient.post<ChatbotSession>('/api/chatbot/sessions');
  return response.data;
}

export async function fetchChatbotSessions(): Promise<ChatbotSessionSummary[]> {
  const response = await apiClient.get<ChatbotSessionSummary[]>('/api/chatbot/sessions');
  return response.data;
}

export async function fetchChatbotSession(sessionId: number): Promise<ChatbotSession> {
  const response = await apiClient.get<ChatbotSession>(`/api/chatbot/sessions/${sessionId}`);
  return response.data;
}

/** 202 PENDING 턴. 답은 fetchChatbotSession 으로 다시 조회해 기다린다 */
export async function askChatbot(sessionId: number, message: string): Promise<ChatbotTurn> {
  const response = await apiClient.post<ChatbotTurn>(`/api/chatbot/sessions/${sessionId}/turns`, { message });
  return response.data;
}

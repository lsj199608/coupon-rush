import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { tokenStore } from '../api/tokenStore';
import { resetRefreshStateForTest } from '../api/client';

/**
 * 이벤트 상세 화면이 채팅방(WebSocket)에 연결하므로, 모든 화면 테스트에서 실제 연결을 막는다 — 연결 안 됨(아무 일 없음)이 기본.
 * 채팅을 검사하는 테스트는 vi.mocked(connectWebChat) 으로 바꾸고, 연결 코드 자체의 테스트(api/webChat.test.ts)는 vi.unmock 한다.
 */
vi.mock('../api/webChat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/webChat')>();
  return {
    ...actual,
    connectWebChat: vi.fn(() => ({ send: () => false, close: () => {} })),
    fetchWebChatHistory: vi.fn(async () => ({ messages: [], nextBeforeId: null })),
    issueWebChatTicket: vi.fn(),
  };
});

afterEach(() => {
  cleanup();
  tokenStore.clear();
  resetRefreshStateForTest();
  sessionStorage.clear();
});

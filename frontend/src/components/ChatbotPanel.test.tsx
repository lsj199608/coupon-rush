import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/auth';
import * as chatbotApi from '../api/chatbot';
import type { ChatbotSession, ChatbotTurn } from '../api/chatbot';
import { tokenStore } from '../api/tokenStore';
import { CHATBOT_POLLING } from '../hooks/useChatbot';
import { apiError, renderApp } from '../test/renderApp';
import { linkify } from './ChatbotPanel';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
// 챗봇은 아무 화면에서나 열린다 — 첫 화면(이벤트 목록)의 API 는 빈 목록으로
vi.mock('../api/coupons', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/coupons')>();
  return { ...actual, fetchEvents: vi.fn().mockResolvedValue([]) };
});
vi.mock('../api/chatbot', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/chatbot')>();
  return {
    ...actual,
    createChatbotSession: vi.fn(),
    fetchChatbotSessions: vi.fn(),
    fetchChatbotSession: vi.fn(),
    askChatbot: vi.fn(),
  };
});

const ORIGINAL_INTERVAL = CHATBOT_POLLING.intervalMs;

function loggedIn(id = 7) {
  vi.mocked(authApi.restoreSession).mockImplementation(async () => {
    tokenStore.set('access-token');
    return 'access-token';
  });
  vi.mocked(authApi.fetchMe).mockResolvedValue({ id, provider: 'KAKAO', nickname: '홍길동', email: null, role: 'USER' });
}

function turn(overrides: Partial<ChatbotTurn> = {}): ChatbotTurn {
  const now = Date.now();
  return {
    turnId: 1,
    question: '내 쿠폰 보여줘',
    status: 'COMPLETED',
    answer: '받은 쿠폰은 1개예요.',
    failureReason: null,
    retryable: null,
    createdAt: new Date(now).toISOString(),
    deadlineAt: new Date(now + 60_000).toISOString(),
    answeredAt: new Date(now).toISOString(),
    ...overrides,
  };
}

function session(turns: ChatbotTurn[], sessionId = 11): ChatbotSession {
  const now = new Date().toISOString();
  return { sessionId, createdAt: now, lastMessageAt: now, turns };
}

async function openChatbot() {
  const user = userEvent.setup();
  renderApp('/');
  await user.click(await screen.findByRole('button', { name: 'AI 챗봇' }));
  return { user, panel: await screen.findByRole('dialog', { name: 'AI 챗봇' }) };
}

beforeEach(() => {
  sessionStorage.clear();
  CHATBOT_POLLING.intervalMs = 20;
});

afterEach(() => {
  CHATBOT_POLLING.intervalMs = ORIGINAL_INTERVAL;
  tokenStore.clear();
  vi.clearAllMocks();
});

describe('챗봇 창', () => {
  it('비로그인이면 챗봇 버튼이 없다', async () => {
    vi.mocked(authApi.restoreSession).mockRejectedValue(apiError(401, 'REFRESH_TOKEN_MISSING'));
    renderApp('/');

    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 챗봇' })).not.toBeInTheDocument();
  });

  it('첫 질문: 대화방을 만들고 → 답을 기다리는 동안 보내기를 막고 → 답과 이벤트 링크를 보여 준다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([]);
    vi.mocked(chatbotApi.createChatbotSession).mockResolvedValue(session([]));
    vi.mocked(chatbotApi.askChatbot).mockResolvedValue(turn({ status: 'PENDING', answer: null }));
    const pending = session([turn({ question: '커피 쿠폰 있어?', status: 'PENDING', answer: null })]);
    const done = session([turn({
      question: '커피 쿠폰 있어?',
      answer: `찾은 이벤트 1개예요.\n- 커피 50% (진행 중) ${window.location.origin}/events/3`,
    })]);
    vi.mocked(chatbotApi.fetchChatbotSession).mockResolvedValueOnce(pending).mockResolvedValueOnce(pending).mockResolvedValue(done);

    const { user, panel } = await openChatbot();
    expect(await within(panel).findByText(/받은 쿠폰을 물어보세요/)).toBeInTheDocument();

    await user.type(within(panel).getByRole('textbox', { name: '질문' }), '커피 쿠폰 있어?{Enter}');

    expect(await within(panel).findByText('답을 쓰고 있어요…')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '답 기다리는 중' })).toBeDisabled();
    expect(chatbotApi.createChatbotSession).toHaveBeenCalledTimes(1);
    expect(chatbotApi.askChatbot).toHaveBeenCalledWith(11, '커피 쿠폰 있어?');

    const link = await within(panel).findByRole('link', { name: `${window.location.origin}/events/3` });
    expect(link).toHaveAttribute('href', '/events/3');
    expect(within(panel).getByText(/찾은 이벤트 1개예요/)).toBeInTheDocument();
    expect(sessionStorage.getItem('coupon-rush:chatbot-session:7')).toBe('11');
  });

  it('창을 다시 열면 최근 대화방을 이어서 보여 준다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([
      { sessionId: 11, title: '내 쿠폰 보여줘', createdAt: '', lastMessageAt: '' },
    ]);
    vi.mocked(chatbotApi.fetchChatbotSession).mockResolvedValue(session([turn()]));

    const { panel } = await openChatbot();

    expect(await within(panel).findByText('받은 쿠폰은 1개예요.')).toBeInTheDocument();
    expect(within(panel).getByText('내 쿠폰 보여줘')).toBeInTheDocument();
    expect(chatbotApi.fetchChatbotSession).toHaveBeenCalledWith(11);
  });

  it('보던 대화방이 없어졌으면(다른 회원 등) 잊고 최근 방을 다시 고른다', async () => {
    loggedIn();
    sessionStorage.setItem('coupon-rush:chatbot-session:7', '99');
    vi.mocked(chatbotApi.fetchChatbotSession).mockImplementation(async (id) => {
      if (id === 99) throw apiError(404, 'CHATBOT_SESSION_NOT_FOUND');
      return session([turn()]);
    });
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([
      { sessionId: 11, title: null, createdAt: '', lastMessageAt: '' },
    ]);

    const { panel } = await openChatbot();

    expect(await within(panel).findByText('받은 쿠폰은 1개예요.')).toBeInTheDocument();
    expect(sessionStorage.getItem('coupon-rush:chatbot-session:7')).toBe('11');
  });

  it('실패한 답은 이유를 알려 주고, 다시 보낼 수 있으면 같은 질문을 다시 보낸다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([{ sessionId: 11, title: null, createdAt: '', lastMessageAt: '' }]);
    vi.mocked(chatbotApi.fetchChatbotSession).mockResolvedValue(session([
      turn({ status: 'FAILED', answer: null, failureReason: 'LLM_UNAVAILABLE', retryable: true }),
      turn({ turnId: 2, question: '이상한 질문', status: 'FAILED', answer: null, failureReason: 'CONTENT_REFUSED', retryable: false }),
    ]));
    vi.mocked(chatbotApi.askChatbot).mockResolvedValue(turn({ status: 'PENDING' }));

    const { user, panel } = await openChatbot();

    expect(await within(panel).findByText(/지금은 답을 만들 수 없어요/)).toBeInTheDocument();
    expect(within(panel).getByText(/이 질문에는 답할 수 없어요/)).toBeInTheDocument();
    const retries = within(panel).getAllByRole('button', { name: '다시 보내기' });
    expect(retries).toHaveLength(1);

    await user.click(retries[0]);

    await waitFor(() => expect(chatbotApi.askChatbot).toHaveBeenCalledWith(11, '내 쿠폰 보여줘'));
  });

  it('접수 거절(한도·대기 중)은 이유를 보여 주고 입력은 남겨 두지 않는다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([{ sessionId: 11, title: null, createdAt: '', lastMessageAt: '' }]);
    vi.mocked(chatbotApi.fetchChatbotSession).mockResolvedValue(session([turn()]));
    vi.mocked(chatbotApi.askChatbot).mockRejectedValue(apiError(429, 'CHATBOT_RATE_LIMITED'));

    const { user, panel } = await openChatbot();
    await within(panel).findByText('받은 쿠폰은 1개예요.');
    await user.type(within(panel).getByRole('textbox', { name: '질문' }), '또 질문{Enter}');

    expect(await within(panel).findByText('질문은 1분에 5번까지예요. 잠시 후 다시 보내 주세요.')).toBeInTheDocument();
  });

  it('새 대화는 빈 화면으로 시작하고, 다음 질문 때 새 대화방을 만든다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([{ sessionId: 11, title: null, createdAt: '', lastMessageAt: '' }]);
    vi.mocked(chatbotApi.fetchChatbotSession).mockImplementation(async (id) => session(id === 11 ? [turn()] : [], id));
    vi.mocked(chatbotApi.createChatbotSession).mockResolvedValue(session([], 12));
    vi.mocked(chatbotApi.askChatbot).mockResolvedValue(turn({ status: 'PENDING' }));

    const { user, panel } = await openChatbot();
    await within(panel).findByText('받은 쿠폰은 1개예요.');

    await user.click(within(panel).getByRole('button', { name: '새 대화' }));
    expect(await within(panel).findByText(/받은 쿠폰을 물어보세요/)).toBeInTheDocument();
    expect(within(panel).queryByText('받은 쿠폰은 1개예요.')).not.toBeInTheDocument();

    await user.type(within(panel).getByRole('textbox', { name: '질문' }), '안녕{Enter}');
    await waitFor(() => expect(chatbotApi.askChatbot).toHaveBeenCalledWith(12, '안녕'));
  });

  it('닫기 버튼과 Esc 로 닫힌다', async () => {
    loggedIn();
    vi.mocked(chatbotApi.fetchChatbotSessions).mockResolvedValue([]);

    const { user, panel } = await openChatbot();
    await user.click(within(panel).getByRole('button', { name: '챗봇 닫기' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'AI 챗봇' }));
    await screen.findByRole('dialog');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('linkify', () => {
  it('HTML 은 글자 그대로, 바깥 주소는 새 탭 링크', () => {
    render(<MemoryRouter><p>{linkify('<img src=x onerror=alert(1)> 참고: https://example.com/a.')}</p></MemoryRouter>);

    expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    const link = screen.getByRole('link', { name: 'https://example.com/a' });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });
});

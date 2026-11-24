import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as webChatApi from '../api/webChat';
import type { WebChatHandlers, WebChatHistoryMessage, WebChatMessage } from '../api/webChat';
import { apiError } from '../test/renderApp';
import { WebChatPanel } from './WebChatPanel';

// connectWebChat·fetchWebChatHistory 는 test/setup.ts 가 vi.fn 으로 바꿔 둔다

const ME = 1;

let seq = 0;
function msg(content: string, extra: Partial<WebChatMessage> = {}): WebChatMessage {
  seq += 1;
  return {
    messageId: `m-${seq}`,
    eventId: 7,
    senderId: 2,
    senderNickname: '철수',
    content,
    sentAt: '2026-11-24T05:00:00Z',
    ...extra,
  };
}
const saved = (m: WebChatMessage, id: number): WebChatHistoryMessage => ({ ...m, id });

/** 연결 대역: 테스트가 서버 쪽 사건(연결·메시지·끊김)을 직접 일으킨다 */
function fakeConnection() {
  let handlers: WebChatHandlers | undefined;
  const send = vi.fn(() => true);
  const close = vi.fn();
  vi.mocked(webChatApi.connectWebChat).mockImplementation((_eventId, _loggedIn, h) => {
    handlers = h;
    return { send, close };
  });
  const h = () => {
    if (!handlers) throw new Error('아직 연결하지 않음');
    return handlers;
  };
  return {
    send,
    close,
    connected: (canSend = true) => act(() => h().onConnected(canSend)),
    disconnected: () => act(() => h().onDisconnected()),
    receive: (m: WebChatMessage) => act(() => h().onMessage(m)),
    reject: (code: string) => act(() => h().onRejected({ code, message: code })),
  };
}

function renderPanel(userId: number | null = ME) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WebChatPanel eventId={7} userId={userId} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const list = () => screen.getByRole('list');
/** 헤더의 연결 상태 (불러오는 중 Spinner 도 role=status 라 클래스로 고른다) */
const connectionStatus = () => document.querySelector('.web-chat-status')!;
const history = vi.mocked(webChatApi.fetchWebChatHistory);

beforeEach(() => {
  history.mockResolvedValue({ messages: [], nextBeforeId: null });
});

describe('이벤트 채팅', () => {
  it('구독(연결)이 끝난 뒤에 지난 대화를 읽는다 — 그 사이 메시지를 놓치지 않게', async () => {
    const conn = fakeConnection();
    renderPanel();
    expect(webChatApi.connectWebChat).toHaveBeenCalledWith(7, true, expect.anything());
    expect(connectionStatus()).toHaveTextContent('연결 중');
    expect(history).not.toHaveBeenCalled();

    conn.connected();
    expect(connectionStatus()).toHaveTextContent('연결됨');
    await waitFor(() => expect(history).toHaveBeenCalledWith(7, undefined));
    expect(await screen.findByText('아직 대화가 없어요. 첫 메시지를 남겨 보세요.')).toBeInTheDocument();
  });

  it('지난 대화 + 실시간 메시지를 messageId 로 한 번만, 지난 대화 순서대로', async () => {
    const conn = fakeConnection();
    const a = msg('첫 번째');
    const b = msg('두 번째');
    const c = msg('세 번째');
    history.mockResolvedValue({ messages: [saved(a, 1), saved(b, 2)], nextBeforeId: null });
    renderPanel();

    conn.connected();
    conn.receive(b);   // 구독 직후 받은 메시지가 지난 대화에도 있음
    await screen.findByText('첫 번째');
    conn.receive(c);

    const items = within(list()).getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent);
    expect(items).toEqual(['첫 번째', '두 번째', '세 번째']);
  });

  it('보내기: 본문만 넘기고 입력칸을 비운다 — 내 메시지는 방으로 돌아와 "나"로 보인다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    renderPanel();
    conn.connected();

    const input = screen.getByRole('textbox', { name: '채팅 메시지' });
    await user.type(input, '  안녕하세요  {Enter}');
    expect(conn.send).toHaveBeenCalledWith('안녕하세요');
    expect(input).toHaveValue('');

    conn.receive(msg('안녕하세요', { senderId: ME, senderNickname: '홍길동' }));
    const mine = screen.getByText('안녕하세요').closest('li')!;
    expect(mine).toHaveClass('mine');
    expect(within(mine).getByText('나')).toBeInTheDocument();
  });

  it('Shift+Enter 는 줄바꿈, 빈 메시지·500자 초과는 보내지 않는다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    renderPanel();
    conn.connected();
    const input = screen.getByRole('textbox', { name: '채팅 메시지' });

    await user.type(input, '   {Enter}');
    await user.type(input, '줄{Shift>}{Enter}{/Shift}바꿈');
    expect(input).toHaveValue('   줄\n바꿈');
    expect(conn.send).not.toHaveBeenCalled();

    await user.clear(input);
    await user.click(input);
    await user.paste('가'.repeat(501));
    expect(screen.getByText('501 / 500')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();
    await user.keyboard('{Enter}');
    expect(conn.send).not.toHaveBeenCalled();
  });

  it('연결 전·읽기 전용 연결이면 보내기를 막는다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    renderPanel();
    await user.type(screen.getByRole('textbox', { name: '채팅 메시지' }), '안녕');
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();

    conn.connected(false);   // 티켓을 못 받아 읽기만으로 붙음
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();
    conn.connected(true);
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled();
  });

  it('거절 사유를 보여 주고, 다음 보내기에서 지운다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    renderPanel();
    conn.connected();

    conn.reject('WEB_CHAT_RATE_LIMITED');
    expect(screen.getByRole('alert')).toHaveTextContent('메시지를 너무 빨리 보내고 있어요');
    await user.type(screen.getByRole('textbox', { name: '채팅 메시지' }), '다시{Enter}');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('비로그인: 읽기만 — 입력칸 대신 로그인 안내', async () => {
    const conn = fakeConnection();
    renderPanel(null);
    expect(webChatApi.connectWebChat).toHaveBeenCalledWith(7, false, expect.anything());
    conn.connected(false);
    conn.receive(msg('누구나 볼 수 있음'));

    expect(screen.getByText('누구나 볼 수 있음')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');
  });

  it('끊기면 안내하고, 다시 연결되면 지난 대화를 다시 읽어 끊긴 동안의 메시지를 채운다', async () => {
    const conn = fakeConnection();
    const a = msg('끊기기 전');
    const missed = msg('끊긴 동안');
    history.mockResolvedValueOnce({ messages: [saved(a, 1)], nextBeforeId: null });
    renderPanel();
    conn.connected();
    await screen.findByText('끊기기 전');

    conn.disconnected();
    expect(connectionStatus()).toHaveTextContent('다시 연결하는 중');
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();

    history.mockResolvedValueOnce({ messages: [saved(a, 1), saved(missed, 2)], nextBeforeId: null });
    conn.connected();
    expect(await screen.findByText('끊긴 동안')).toBeInTheDocument();
    expect(history).toHaveBeenCalledTimes(2);
    expect(screen.getAllByText('끊기기 전')).toHaveLength(1);
  });

  it('[이전 대화 보기]: beforeId 로 더 오래된 페이지를 위에 붙인다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    const old = msg('오래된 메시지');
    const recent = msg('최근 메시지');
    history.mockImplementation(async (_id, beforeId) =>
      beforeId === undefined
        ? { messages: [saved(recent, 10)], nextBeforeId: 10 }
        : { messages: [saved(old, 3)], nextBeforeId: null },
    );
    renderPanel();
    conn.connected();
    await screen.findByText('최근 메시지');

    await user.click(screen.getByRole('button', { name: '이전 대화 보기' }));
    await screen.findByText('오래된 메시지');
    expect(history).toHaveBeenLastCalledWith(7, 10);
    const items = within(list()).getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent);
    expect(items).toEqual(['오래된 메시지', '최근 메시지']);
    expect(screen.queryByRole('button', { name: '이전 대화 보기' })).not.toBeInTheDocument();
  });

  it('지난 대화를 못 읽어도(503) 실시간 대화는 계속되고, [다시 시도]로 다시 읽는다', async () => {
    const user = userEvent.setup();
    const conn = fakeConnection();
    history.mockRejectedValueOnce(apiError(503, 'WEB_CHAT_HISTORY_UNAVAILABLE'));
    renderPanel();
    conn.connected();

    expect(await screen.findByRole('alert')).toHaveTextContent('지난 대화를 불러오지 못했어요');
    conn.receive(msg('실시간은 됨'));
    expect(screen.getByText('실시간은 됨')).toBeInTheDocument();

    history.mockResolvedValueOnce({ messages: [saved(msg('저장된 것'), 1)], nextBeforeId: null });
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('저장된 것')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('본문은 글자로만 그린다 (HTML 이 들어와도 요소가 되지 않음)', () => {
    const conn = fakeConnection();
    const { container } = renderPanel();
    conn.connected();
    conn.receive(msg('<img src=x onerror="alert(1)"><b>굵게</b>'));

    expect(screen.getByText('<img src=x onerror="alert(1)"><b>굵게</b>')).toBeInTheDocument();
    expect(container.querySelector('img, b')).toBeNull();
  });

  it('화면을 떠나면 연결을 닫는다', () => {
    const conn = fakeConnection();
    const { unmount } = renderPanel();
    unmount();
    expect(conn.close).toHaveBeenCalledOnce();
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.unmock('./webChat');   // test/setup.ts 가 화면 테스트용으로 막아 둔 것을 풀고 실제 연결 코드를 검사한다

/** @stomp/stompjs Client 대역: 설정·콜백을 잡아 두고, 연결/메시지를 테스트가 직접 일으킨다 */
const fake = vi.hoisted(() => ({
  instances: [] as FakeClient[],
}));

interface FakeClient {
  config: Record<string, unknown>;
  connectHeaders: Record<string, string>;
  connected: boolean;
  beforeConnect: () => Promise<void>;
  onConnect: () => void;
  onWebSocketClose: () => void;
  subscriptions: Map<string, (frame: { body: string }) => void>;
  publish: ReturnType<typeof vi.fn>;
  activate: ReturnType<typeof vi.fn>;
  deactivate: ReturnType<typeof vi.fn>;
}

vi.mock('@stomp/stompjs', () => ({
  ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 },
  Client: vi.fn(function (this: FakeClient, config: Record<string, unknown>) {
    this.config = config;
    this.connectHeaders = {};
    this.connected = false;
    this.subscriptions = new Map();
    this.publish = vi.fn();
    this.activate = vi.fn();
    this.deactivate = vi.fn(async () => {});
    (this as unknown as { subscribe: unknown }).subscribe = (destination: string, cb: (f: { body: string }) => void) => {
      this.subscriptions.set(destination, cb);
    };
    fake.instances.push(this);
  }),
}));

vi.mock('./client', () => ({ apiClient: { post: vi.fn() } }));

import { apiClient } from './client';
import { connectWebChat, contentLength, type WebChatHandlers } from './webChat';

function handlers(): WebChatHandlers & Record<keyof WebChatHandlers, ReturnType<typeof vi.fn>> {
  return { onConnected: vi.fn(), onDisconnected: vi.fn(), onMessage: vi.fn(), onRejected: vi.fn() };
}

/** 클라이언트가 연결 시도 → 티켓 받기 → 서버가 CONNECTED 를 준 상황 */
async function connect(client: FakeClient) {
  await client.beforeConnect();
  client.connected = true;
  client.onConnect();
}

const ticketResponse = (ticket: string) => ({ data: { ticket, expiresAt: new Date().toISOString() } });

beforeEach(() => {
  fake.instances.length = 0;
  vi.mocked(apiClient.post).mockReset();
});

describe('connectWebChat', () => {
  it('같은 출처의 /ws/web-chat 으로, 지수 백오프 재연결·하트비트 10초', () => {
    connectWebChat(1, false, handlers());
    const [client] = fake.instances;
    expect(client.config).toMatchObject({
      brokerURL: `ws://${window.location.host}/ws/web-chat`,
      reconnectDelay: 1_000,
      maxReconnectDelay: 30_000,
      reconnectTimeMode: 1,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
    });
    expect(client.activate).toHaveBeenCalledOnce();
  });

  it('로그인: 연결할 때마다 새 티켓을 받아 CONNECT 헤더에 싣는다 (티켓은 1회용)', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce(ticketResponse('t-1')).mockResolvedValueOnce(ticketResponse('t-2'));
    const h = handlers();
    connectWebChat(7, true, h);
    const [client] = fake.instances;

    await connect(client);
    expect(client.connectHeaders).toEqual({ ticket: 't-1' });
    expect([...client.subscriptions.keys()]).toEqual(['/topic/events/7', '/user/queue/errors']);
    expect(h.onConnected).toHaveBeenLastCalledWith(true);

    client.onWebSocketClose();   // 끊김 → 클라이언트가 다시 연결
    expect(h.onDisconnected).toHaveBeenCalledOnce();
    await connect(client);
    expect(client.connectHeaders).toEqual({ ticket: 't-2' });
    expect(apiClient.post).toHaveBeenCalledTimes(2);
    expect(apiClient.post).toHaveBeenCalledWith('/api/web-chat/tickets');
  });

  it('티켓을 못 받으면 읽기만으로 연결한다 (예외로 연결이 멈추지 않게) — 다음 연결 때 다시 받아 본다', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce(new Error('503')).mockResolvedValueOnce(ticketResponse('t-ok'));
    const h = handlers();
    const conn = connectWebChat(7, true, h);
    const [client] = fake.instances;

    await connect(client);
    expect(client.connectHeaders).toEqual({});
    expect([...client.subscriptions.keys()]).toEqual(['/topic/events/7']);
    expect(h.onConnected).toHaveBeenLastCalledWith(false);
    expect(conn.send('안녕')).toBe(false);
    expect(client.publish).not.toHaveBeenCalled();

    await connect(client);
    expect(client.connectHeaders).toEqual({ ticket: 't-ok' });
    expect(h.onConnected).toHaveBeenLastCalledWith(true);
  });

  it('비로그인은 티켓을 요청하지 않고 보내지도 않는다', async () => {
    const conn = connectWebChat(7, false, handlers());
    await connect(fake.instances[0]);
    expect(apiClient.post).not.toHaveBeenCalled();
    expect(conn.send('안녕')).toBe(false);
  });

  it('보내기는 본문만 JSON 으로 (보낸 사람은 서버가 채운다)', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(ticketResponse('t'));
    const conn = connectWebChat(7, true, handlers());
    const [client] = fake.instances;
    expect(conn.send('연결 전')).toBe(false);

    await connect(client);
    expect(conn.send('안녕하세요')).toBe(true);
    expect(client.publish).toHaveBeenCalledWith({ destination: '/app/events/7/chat', body: '{"content":"안녕하세요"}' });
  });

  it('받은 메시지·거절을 넘기고, 읽을 수 없는 본문은 버린다', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(ticketResponse('t'));
    const h = handlers();
    connectWebChat(7, true, h);
    const [client] = fake.instances;
    await connect(client);

    const message = { messageId: 'm1', eventId: 7, senderId: 2, senderNickname: '철수', content: 'hi', sentAt: '2026-11-24T00:00:00Z' };
    client.subscriptions.get('/topic/events/7')!({ body: JSON.stringify(message) });
    client.subscriptions.get('/topic/events/7')!({ body: 'not json' });
    client.subscriptions.get('/topic/events/7')!({ body: '{"foo":1}' });
    client.subscriptions.get('/user/queue/errors')!({ body: '{"code":"WEB_CHAT_RATE_LIMITED","message":"m"}' });

    expect(h.onMessage).toHaveBeenCalledTimes(1);
    expect(h.onMessage).toHaveBeenCalledWith(message);
    expect(h.onRejected).toHaveBeenCalledWith({ code: 'WEB_CHAT_RATE_LIMITED', message: 'm' });
  });

  it('닫은 뒤에는 끊김을 알리지 않고 보내지 않는다', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(ticketResponse('t'));
    const h = handlers();
    const conn = connectWebChat(7, true, h);
    const [client] = fake.instances;
    await connect(client);

    conn.close();
    client.onWebSocketClose();
    expect(client.deactivate).toHaveBeenCalledOnce();
    expect(h.onDisconnected).not.toHaveBeenCalled();
    expect(conn.send('늦은 메시지')).toBe(false);
  });
});

describe('contentLength', () => {
  it('앞뒤 공백을 빼고 글자(코드 포인트)로 센다 — 이모지 하나 = 1', () => {
    expect(contentLength('  안녕  ')).toBe(2);
    expect(contentLength('👍👍')).toBe(2);
  });
});

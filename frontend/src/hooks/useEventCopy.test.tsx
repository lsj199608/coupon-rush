import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider, notifyManager } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as llmApi from '../api/llm';
import type { EventCopyInput, LlmRequestResult } from '../api/llm';
import { EVENT_COPY_POLLING, useEventCopy } from './useEventCopy';

vi.mock('../api/llm', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/llm')>();
  return { ...actual, requestEventCopy: vi.fn(), fetchLlmRequest: vi.fn() };
});

/** 실제 값(2초 간격 / 75초 타임아웃)을 가짜 시계로 검증한다 (useIssueCoupon.test.tsx 와 같은 방식) */
const INPUT: EventCopyInput = { keywords: '겨울', tone: 'FRIENDLY', totalQuantity: 10, regenerate: false };

function result(status: LlmRequestResult['status'], extra: Partial<LlmRequestResult> = {}): LlmRequestResult {
  return {
    requestId: 'llm-1', type: 'EVENT_COPY', status, candidates: [], failureReason: null, retryable: null,
    model: 'fake', inputTokens: 0, outputTokens: 0, cached: false,
    createdAt: '2030-01-01T00:00:00Z', deadlineAt: '2030-01-01T00:01:00Z', completedAt: null, ...extra,
  };
}

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useEventCopy(), { wrapper });
}

const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));
const pollCalls = () => vi.mocked(llmApi.fetchLlmRequest).mock.calls.length;

describe('useEventCopy 폴링', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    notifyManager.setScheduler((callback) => callback());
    expect(EVENT_COPY_POLLING).toEqual({ intervalMs: 2_000, timeoutMs: 75_000 });
    vi.mocked(llmApi.requestEventCopy).mockResolvedValue({ requestId: 'llm-1', status: 'PENDING', deadlineAt: '' });
  });

  afterEach(() => {
    vi.useRealTimers();
    notifyManager.setScheduler((callback) => setTimeout(callback, 0));
  });

  it('PENDING 이면 2초마다 다시 묻고, COMPLETED 면 멈추고 후보를 넘긴다', async () => {
    const candidates = [{ name: 'a', description: 'b' }];
    vi.mocked(llmApi.fetchLlmRequest)
      .mockResolvedValueOnce(result('PENDING'))
      .mockResolvedValue(result('COMPLETED', { candidates, cached: true }));
    const { result: hook } = setup();

    act(() => hook.current.generate(INPUT));
    await advance(0);
    expect(hook.current.state.phase).toBe('pending');
    expect(hook.current.busy).toBe(true);
    expect(pollCalls()).toBe(1);

    await advance(1_999);
    expect(pollCalls()).toBe(1);
    await advance(1);
    expect(pollCalls()).toBe(2);
    expect(hook.current.state).toEqual({ phase: 'completed', candidates, cached: true });

    await advance(10_000);
    expect(pollCalls()).toBe(2);
    expect(hook.current.busy).toBe(false);
  });

  it('FAILED 면 멈추고 사유와 재시도 가능 여부를 넘긴다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(result('FAILED', { failureReason: 'INVALID_OUTPUT', retryable: true }));
    const { result: hook } = setup();

    act(() => hook.current.generate(INPUT));
    await advance(0);
    await advance(6_000);

    expect(hook.current.state).toEqual({ phase: 'failed', reason: 'INVALID_OUTPUT', retryable: true });
    expect(pollCalls()).toBe(1);
  });

  it('75초 동안 PENDING 이면 timeout 으로 바꾸고 폴링을 멈춘다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(result('PENDING'));
    const { result: hook } = setup();

    act(() => hook.current.generate(INPUT));
    await advance(0);
    await advance(74_000);
    expect(hook.current.state.phase).toBe('pending');
    const before = pollCalls();
    expect(before).toBe(38);   // 0초 + 2~74초(2초 간격)

    await advance(1_000);
    expect(hook.current.state.phase).toBe('timeout');
    await advance(20_000);
    expect(pollCalls()).toBeLessThanOrEqual(before + 1);
  });

  it('연달아 눌러도 요청은 한 번만 간다 (비용이 드는 요청)', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(result('PENDING'));
    const { result: hook } = setup();

    act(() => {
      hook.current.generate(INPUT);
      hook.current.generate(INPUT);
      hook.current.generate(INPUT);
    });
    await advance(0);

    expect(llmApi.requestEventCopy).toHaveBeenCalledTimes(1);
  });

  it('끝난 뒤에는 다시 생성할 수 있다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(result('COMPLETED', { candidates: [{ name: 'a', description: 'b' }] }));
    const { result: hook } = setup();

    act(() => hook.current.generate(INPUT));
    await advance(0);
    expect(hook.current.state.phase).toBe('completed');

    act(() => hook.current.generate({ ...INPUT, regenerate: true }));
    await advance(0);

    expect(llmApi.requestEventCopy).toHaveBeenCalledTimes(2);
    expect(vi.mocked(llmApi.requestEventCopy).mock.calls[1][0].regenerate).toBe(true);
  });
});

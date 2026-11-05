import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider, notifyManager } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as couponsApi from '../api/coupons';
import type { IssueRequestResult } from '../api/coupons';
import { queryKeys } from '../api/queryKeys';
import { ISSUE_POLLING, useIssueCoupon } from './useIssueCoupon';

vi.mock('../api/coupons', () => ({ requestIssue: vi.fn(), fetchIssueRequest: vi.fn() }));

/**
 * 폴링 간격·타임아웃을 실제 값(1초 / 30초) 그대로 가짜 시계로 검증한다.
 * (화면 테스트 EventPages.test.tsx 는 값을 짧게 줄여 결과 UI 를 본다 — 여기는 "몇 초마다 몇 번"을 본다)
 */
function result(status: IssueRequestResult['status'], extra: Partial<IssueRequestResult> = {}): IssueRequestResult {
  return { requestId: 'req-1', couponId: 1, status, requestedAt: '2030-01-01T00:00:00Z', ...extra };
}

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const hook = renderHook(() => useIssueCoupon(1), { wrapper });
  return { ...hook, invalidate };
}

/** 가짜 시계를 ms 만큼 진행하고, 그 사이 끝난 Promise·리렌더까지 반영 */
const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));
const pollCalls = () => vi.mocked(couponsApi.fetchIssueRequest).mock.calls.length;

describe('useIssueCoupon 폴링', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // TanStack Query 는 캐시 변경을 setTimeout(0) 으로 모아 React 에 알린다 → 가짜 시계에서는 알림이 밀리므로 바로 알리게 한다
    notifyManager.setScheduler((callback) => callback());
    expect(ISSUE_POLLING).toEqual({ intervalMs: 1_000, timeoutMs: 30_000 });
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'accepted', requestId: 'req-1' });
  });

  afterEach(() => {
    vi.useRealTimers();
    notifyManager.setScheduler((callback) => setTimeout(callback, 0));
  });

  it('PENDING 이면 1초마다 다시 묻고, COMPLETED 가 오면 멈춘 뒤 목록·내 쿠폰을 무효화한다', async () => {
    vi.mocked(couponsApi.fetchIssueRequest)
      .mockResolvedValueOnce(result('PENDING'))
      .mockResolvedValueOnce(result('PENDING'))
      .mockResolvedValue(result('COMPLETED', { couponIssueId: 9 }));
    const { result: hook, invalidate } = setup();

    act(() => hook.current.issue());
    await advance(0);
    expect(hook.current.state).toEqual({ phase: 'pending', requestId: 'req-1' });
    expect(pollCalls()).toBe(1);   // 202 직후 바로 한 번

    await advance(999);
    expect(pollCalls()).toBe(1);   // 1초가 되기 전에는 다시 묻지 않는다
    await advance(1);
    expect(pollCalls()).toBe(2);
    await advance(1_000);
    expect(pollCalls()).toBe(3);
    expect(hook.current.state.phase).toBe('completed');

    await advance(5_000);
    expect(pollCalls()).toBe(3);   // 완료 후에는 더 묻지 않는다
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.events });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.myCoupons });
  });

  it('FAILED 가 오면 멈추고 사유를 넘긴다', async () => {
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(result('FAILED', { failureReason: 'RETRY_EXHAUSTED' }));
    const { result: hook } = setup();

    act(() => hook.current.issue());
    await advance(0);
    await advance(3_000);

    expect(hook.current.state).toEqual({ phase: 'failed', reason: 'RETRY_EXHAUSTED' });
    expect(pollCalls()).toBe(1);
  });

  it('30초 동안 계속 PENDING 이면 timeout 으로 바꾸고 폴링을 멈춘다', async () => {
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(result('PENDING'));
    const { result: hook } = setup();

    act(() => hook.current.issue());
    await advance(0);
    await advance(29_000);
    expect(hook.current.state.phase).toBe('pending');
    const callsBeforeTimeout = pollCalls();
    expect(callsBeforeTimeout).toBe(30);   // 0초 + 1~29초

    await advance(1_000);
    expect(hook.current.state.phase).toBe('timeout');

    await advance(10_000);
    expect(pollCalls()).toBeLessThanOrEqual(callsBeforeTimeout + 1);   // 타임아웃 순간 겹친 1회 외에는 없음
  });

  it('issue 를 연달아 불러도 요청은 한 번만 간다 (렌더링 전 연타 방지)', async () => {
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(result('COMPLETED', { couponIssueId: 1 }));
    const { result: hook } = setup();

    act(() => {
      hook.current.issue();
      hook.current.issue();
      hook.current.issue();
    });
    await advance(0);

    expect(couponsApi.requestIssue).toHaveBeenCalledTimes(1);
  });

  it('reset 후에는 다시 요청할 수 있다', async () => {
    vi.mocked(couponsApi.requestIssue).mockRejectedValueOnce(new Error('503'));
    const { result: hook } = setup();

    act(() => hook.current.issue());
    await advance(0);
    expect(hook.current.state.phase).toBe('rejected');

    act(() => hook.current.reset());
    await advance(0);
    expect(hook.current.state.phase).toBe('idle');
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(result('COMPLETED', { couponIssueId: 1 }));
    act(() => hook.current.issue());
    await advance(0);

    expect(couponsApi.requestIssue).toHaveBeenCalledTimes(2);
  });
});

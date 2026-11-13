import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/auth';
import * as couponsApi from '../api/coupons';
import type { CouponEvent } from '../api/coupons';
import * as searchApi from '../api/search';
import type { EventSearchResult } from '../api/search';
import { EVENT_SEARCH_POLLING } from '../hooks/useEventSearch';
import { apiError, renderApp } from '../test/renderApp';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
vi.mock('../api/coupons', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/coupons')>();
  return { ...actual, fetchEvents: vi.fn() };
});
vi.mock('../api/search', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/search')>();
  return { ...actual, startEventSearch: vi.fn(), fetchEventSearch: vi.fn() };
});

const HOUR = 60 * 60 * 1000;

function event(id: number, name: string): CouponEvent {
  const now = Date.now();
  return {
    id, name, description: null, totalQuantity: 100, issuedQuantity: 0, remainingQuantity: 100,
    issueStartAt: new Date(now - HOUR).toISOString(), issueEndAt: new Date(now + HOUR).toISOString(),
    status: 'OPEN', serverTime: new Date(now).toISOString(),
  };
}

function result(overrides: Partial<EventSearchResult>): EventSearchResult {
  return {
    requestId: 'search-1', status: 'PENDING', query: '진행 중인 커피', filter: null, fallbackReason: null,
    keywords: [], events: [], deadlineAt: null, ...overrides,
  };
}

const completed = result({
  status: 'COMPLETED',
  filter: { keywords: ['커피'], statuses: ['OPEN'], startFrom: null, startTo: null, endFrom: null, endTo: null, availableOnly: false },
  events: [event(1, '커피 할인')],
});

async function search(text: string) {
  await userEvent.type(await screen.findByRole('searchbox', { name: '이벤트 검색' }), text);
  await userEvent.click(screen.getByRole('button', { name: '검색' }));
}

describe('이벤트 자연어 검색', () => {
  beforeEach(() => {
    vi.mocked(authApi.restoreSession).mockRejectedValue(new Error('no cookie'));   // 비로그인도 검색할 수 있다
    vi.mocked(couponsApi.fetchEvents).mockResolvedValue([event(1, '커피 할인'), event(2, '치킨 반값')]);
    EVENT_SEARCH_POLLING.intervalMs = 20;
    EVENT_SEARCH_POLLING.timeoutMs = 500;
  });

  afterEach(() => {
    EVENT_SEARCH_POLLING.intervalMs = 1_000;
    EVENT_SEARCH_POLLING.timeoutMs = 25_000;
  });

  it('검색하면 AI 조건을 만드는 동안 기다렸다가, 조건과 찾은 이벤트를 보여 준다', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({}));
    vi.mocked(searchApi.fetchEventSearch).mockResolvedValueOnce(result({})).mockResolvedValue(completed);

    renderApp('/');
    await screen.findByText('치킨 반값');
    await search('  진행 중인   커피 ');

    expect(searchApi.startEventSearch).toHaveBeenCalledWith('진행 중인 커피');
    const summary = await screen.findByLabelText('검색 조건');
    expect(within(summary).getByText('AI가 이렇게 찾았어요')).toBeInTheDocument();
    expect(within(summary).getByText('"커피"')).toBeInTheDocument();
    expect(within(summary).getByText('진행 중')).toBeInTheDocument();
    expect(screen.getByText('커피 할인').closest('a')).toHaveAttribute('href', '/events/1');
    expect(screen.queryByText('치킨 반값')).not.toBeInTheDocument();
    expect(searchApi.fetchEventSearch).toHaveBeenCalledWith('search-1');
  });

  it('AI 를 못 쓰면 서버가 준 키워드 검색 결과와 이유를 보여 준다 (접수에서 바로)', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({
      requestId: null, status: 'FALLBACK', fallbackReason: 'RATE_LIMITED', keywords: ['치킨'], events: [event(2, '치킨 반값')],
    }));

    renderApp('/?q=치킨');

    expect(await screen.findByText(/너무 자주 써서 이번엔 단어로 찾았어요/)).toBeInTheDocument();
    expect(screen.getByText('"치킨"')).toBeInTheDocument();
    expect(screen.getByText('치킨 반값')).toBeInTheDocument();
    expect(searchApi.fetchEventSearch).not.toHaveBeenCalled();
  });

  it('폴링 중 서버가 TIMEOUT 키워드 검색으로 끝내면 그 결과를 보여 준다', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({}));
    vi.mocked(searchApi.fetchEventSearch).mockResolvedValue(result({
      status: 'FALLBACK', fallbackReason: 'TIMEOUT', keywords: ['커피'], events: [event(1, '커피 할인')],
    }));

    renderApp('/?q=커피');

    expect(await screen.findByText(/오래 걸려 단어로 찾았어요/)).toBeInTheDocument();
    expect(screen.getByText('커피 할인')).toBeInTheDocument();
  });

  it('결과가 없으면 안내, [전체 보기]를 누르면 전체 목록으로 돌아간다', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({ ...completed, events: [] }));

    renderApp('/?q=없는거');

    expect(await screen.findByText('조건에 맞는 이벤트가 없어요.')).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('없는거');
    await userEvent.click(screen.getByRole('button', { name: '전체 보기' }));
    expect(await screen.findByText('치킨 반값')).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });

  it('빈 조건이면 조건 없이 전체라고 알려 준다', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({
      ...completed, filter: { ...completed.filter!, keywords: [], statuses: [] },
    }));

    renderApp('/?q=쿠폰 보여줘');

    expect(await screen.findByText('조건 없이 전체 이벤트를 보여 드려요.')).toBeInTheDocument();
  });

  it('접수가 실패하면 에러 안내와 [다시 검색], 누르면 다시 접수한다', async () => {
    vi.mocked(searchApi.startEventSearch)
      .mockRejectedValueOnce(apiError(400, 'INVALID_INPUT'))
      .mockResolvedValue(completed);

    renderApp('/?q=커피');

    expect(await screen.findByRole('alert')).toHaveTextContent('요청 값이 올바르지 않아요.');
    await userEvent.click(screen.getByRole('button', { name: '다시 검색' }));
    expect(await screen.findByText('커피 할인')).toBeInTheDocument();
    expect(searchApi.startEventSearch).toHaveBeenCalledTimes(2);
  });

  it('서버가 끝까지 PENDING 이면 화면 대기 한도 후 다시 검색을 권한다', async () => {
    vi.mocked(searchApi.startEventSearch).mockResolvedValue(result({}));
    vi.mocked(searchApi.fetchEventSearch).mockResolvedValue(result({}));

    renderApp('/?q=커피');

    expect(await screen.findByText('검색이 너무 오래 걸려요. 다시 검색해 주세요.', {}, { timeout: 2_000 })).toBeInTheDocument();
  });

  it('검색창을 비우고 검색하면 검색하지 않고 전체 목록', async () => {
    renderApp('/');
    await screen.findByText('치킨 반값');

    await userEvent.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => expect(screen.getByText('커피 할인')).toBeInTheDocument());
    expect(searchApi.startEventSearch).not.toHaveBeenCalled();
  });
});

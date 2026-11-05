import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/auth';
import * as couponsApi from '../api/coupons';
import type { CouponEvent, IssueRequestResult } from '../api/coupons';
import { tokenStore } from '../api/tokenStore';
import { ISSUE_POLLING } from '../hooks/useIssueCoupon';
import { apiError, renderApp } from '../test/renderApp';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
vi.mock('../api/coupons', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/coupons')>();
  return {
    ...actual,
    fetchEvents: vi.fn(),
    fetchEvent: vi.fn(),
    requestIssue: vi.fn(),
    fetchIssueRequest: vi.fn(),
    fetchMyCoupons: vi.fn(),
  };
});

const HOUR = 60 * 60 * 1000;

function event(overrides: Partial<CouponEvent> = {}): CouponEvent {
  const now = Date.now();
  return {
    id: 1,
    name: '오픈 기념 쿠폰',
    totalQuantity: 100,
    issuedQuantity: 30,
    remainingQuantity: 70,
    issueStartAt: new Date(now - HOUR).toISOString(),
    issueEndAt: new Date(now + HOUR).toISOString(),
    status: 'OPEN',
    serverTime: new Date(now).toISOString(),
    ...overrides,
  };
}

function issueRequest(status: IssueRequestResult['status'], extra: Partial<IssueRequestResult> = {}): IssueRequestResult {
  return { requestId: 'req-1', couponId: 1, status, requestedAt: new Date().toISOString(), ...extra };
}

function loggedIn() {
  vi.mocked(authApi.restoreSession).mockImplementation(async () => {
    tokenStore.set('access-token');
    return 'access-token';
  });
  vi.mocked(authApi.fetchMe).mockResolvedValue({ id: 1, provider: 'KAKAO', nickname: '홍길동', email: null, role: 'USER' });
}

const issueButton = () => screen.findByRole('button', { name: /발급 받기|요청 중|처리 중|발급 완료|소진|오픈 전|종료된 이벤트/ });

describe('이벤트 목록', () => {
  beforeEach(loggedIn);

  it('이벤트마다 상태 뱃지(오픈 전 / 진행 중 / 종료)와 남은 수량을 보여 준다', async () => {
    vi.mocked(couponsApi.fetchEvents).mockResolvedValue([
      event({ id: 1, name: '진행 이벤트', status: 'OPEN' }),
      event({ id: 2, name: '예정 이벤트', status: 'UPCOMING' }),
      event({ id: 3, name: '지난 이벤트', status: 'ENDED', remainingQuantity: 0 }),
    ]);

    renderApp('/');

    const open = (await screen.findByText('진행 이벤트')).closest('a')!;
    expect(within(open).getByText('진행 중')).toBeInTheDocument();
    expect(within(open).getByText(/남은 수량 70 \/ 100/)).toBeInTheDocument();
    expect(within(screen.getByText('예정 이벤트').closest('a')!).getByText('오픈 전')).toBeInTheDocument();
    expect(within(screen.getByText('지난 이벤트').closest('a')!).getByText('종료')).toBeInTheDocument();
    expect(open).toHaveAttribute('href', '/events/1');
  });

  it('이벤트가 없으면 안내 문구', async () => {
    vi.mocked(couponsApi.fetchEvents).mockResolvedValue([]);

    renderApp('/');

    expect(await screen.findByText('진행 중인 이벤트가 없어요.')).toBeInTheDocument();
  });

  it('불러오기에 실패하면 에러 안내와 다시 시도 버튼, 누르면 다시 불러온다', async () => {
    vi.mocked(couponsApi.fetchEvents)
      .mockRejectedValueOnce(apiError(500, 'INTERNAL'))
      .mockResolvedValue([event({ name: '복구된 이벤트' })]);

    renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent('문제가 생겼어요');
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('복구된 이벤트')).toBeInTheDocument();
  });
});

describe('이벤트 상세와 발급', () => {
  beforeEach(() => {
    loggedIn();
    ISSUE_POLLING.intervalMs = 20;
    ISSUE_POLLING.timeoutMs = 300;
  });

  afterEach(() => {
    ISSUE_POLLING.intervalMs = 1_000;
    ISSUE_POLLING.timeoutMs = 30_000;
  });

  it('오픈 전에는 버튼이 꺼져 있고 서버 시각 기준 카운트다운을 보여 준다', async () => {
    const now = Date.now();
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(
      event({ status: 'UPCOMING', issueStartAt: new Date(now + 90_000).toISOString(), issueEndAt: new Date(now + HOUR).toISOString() }),
    );

    renderApp('/events/1');

    expect(await screen.findByText(/00:01:(29|30)/)).toBeInTheDocument();
    expect(await issueButton()).toHaveTextContent('오픈 전');
    expect(await issueButton()).toBeDisabled();
  });

  it('내 PC 시계가 늦어도 서버 시각 기준으로 오픈됐으면 버튼이 켜진다', async () => {
    const serverNow = Date.now() + 10 * 60_000;   // 서버가 10분 빠름 (= 내 시계가 10분 늦음)
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(
      event({
        status: 'OPEN',
        issueStartAt: new Date(serverNow - 60_000).toISOString(),   // 서버 기준 1분 전 오픈 (내 시계로는 9분 뒤)
        issueEndAt: new Date(serverNow + HOUR).toISOString(),
        serverTime: new Date(serverNow).toISOString(),
      }),
    );

    renderApp('/events/1');

    await waitFor(async () => expect(await issueButton()).toBeEnabled());
    expect(screen.queryByText(/오픈까지/)).not.toBeInTheDocument();
  });

  it('누르면 즉시 버튼이 꺼지고(중복 클릭 방지) 처리 중 → 발급 완료까지 폴링한다', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'accepted', requestId: 'req-1' });
    vi.mocked(couponsApi.fetchIssueRequest)
      .mockResolvedValueOnce(issueRequest('PENDING'))
      .mockResolvedValueOnce(issueRequest('PENDING'))
      .mockResolvedValue(issueRequest('COMPLETED', { couponIssueId: 7 }));

    renderApp('/events/1');
    const button = await screen.findByRole('button', { name: '발급 받기' });

    fireEvent.click(button);
    fireEvent.click(button);   // 렌더링 전에 연달아 눌러도
    fireEvent.click(button);

    expect(button).toBeDisabled();
    expect(await screen.findByText(/발급을 처리하고 있어요/)).toBeInTheDocument();
    expect(await screen.findByText('쿠폰이 발급되었어요! 🎉')).toBeInTheDocument();
    expect(couponsApi.requestIssue).toHaveBeenCalledTimes(1);
    expect(couponsApi.fetchIssueRequest).toHaveBeenCalledWith('req-1');
    const callsAtCompletion = vi.mocked(couponsApi.fetchIssueRequest).mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(vi.mocked(couponsApi.fetchIssueRequest).mock.calls.length).toBe(callsAtCompletion);   // COMPLETED 후 폴링 중단
    expect(await issueButton()).toBeDisabled();
  });

  it.each([
    ['COUPON_SOLD_OUT', 409, '아쉽게도 쿠폰이 모두 소진되었어요.'],
    ['COUPON_ALREADY_ISSUED', 409, '이미 발급받은 쿠폰이에요.'],
    ['COUPON_NOT_IN_PERIOD', 400, '지금은 발급 기간이 아니에요.'],
  ])('즉시 거절 %s → 코드별 안내, 다시 눌러도 결과가 같으므로 버튼은 꺼진 채로', async (code, status, message) => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockRejectedValue(apiError(status, code));

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
    expect(await issueButton()).toBeDisabled();
    expect(couponsApi.fetchIssueRequest).not.toHaveBeenCalled();
  });

  it.each([
    ['COUPON_ISSUE_UNAVAILABLE', 503, '요청이 몰려 접수하지 못했어요'],
    ['COUPON_STOCK_NOT_READY', 503, '발급 준비 중이에요'],
  ])('일시적 거절 %s → 안내 + 다시 시도', async (code, status, message) => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockRejectedValueOnce(apiError(status, code));

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('button', { name: '발급 받기' })).toBeEnabled();
  });

  it('접수 후 DB 저장이 실패하면(FAILED) 사유별 안내 + 다시 시도', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'accepted', requestId: 'req-1' });
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(issueRequest('FAILED', { failureReason: 'RETRY_EXHAUSTED' }));

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('발급 처리 중 문제가 생겼어요');
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('button', { name: '발급 받기' })).toBeEnabled();
  });

  it('FAILED 사유가 이미 발급받음이면 이미 발급 안내 (다시 시도 없음)', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'accepted', requestId: 'req-1' });
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(issueRequest('FAILED', { failureReason: 'COUPON_ALREADY_ISSUED' }));

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByText('이미 발급받은 쿠폰이에요.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
  });

  it('정해진 시간 동안 계속 PENDING 이면 폴링을 멈추고 지연 안내', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'accepted', requestId: 'req-1' });
    vi.mocked(couponsApi.fetchIssueRequest).mockResolvedValue(issueRequest('PENDING'));

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByText(/처리가 늦어지고 있어요/, {}, { timeout: 2_000 })).toBeInTheDocument();
    const callsAtTimeout = vi.mocked(couponsApi.fetchIssueRequest).mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(vi.mocked(couponsApi.fetchIssueRequest).mock.calls.length).toBe(callsAtTimeout);
    expect(screen.getByRole('link', { name: '내 쿠폰 보기' })).toHaveAttribute('href', '/me/coupons');
  });

  it('동기 모드(201)로 바로 발급되면 폴링 없이 완료', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());
    vi.mocked(couponsApi.requestIssue).mockResolvedValue({ kind: 'issued', issueId: 3 });

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByText('쿠폰이 발급되었어요! 🎉')).toBeInTheDocument();
    expect(couponsApi.fetchIssueRequest).not.toHaveBeenCalled();
  });

  it('남은 수량이 0 이면 누르기 전부터 소진으로 꺼져 있다', async () => {
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event({ remainingQuantity: 0, issuedQuantity: 100 }));

    renderApp('/events/1');

    expect(await issueButton()).toHaveTextContent('소진');
    expect(await issueButton()).toBeDisabled();
  });

  it('로그인하지 않고 누르면 로그인 화면으로 보낸다', async () => {
    vi.mocked(authApi.restoreSession).mockRejectedValue(new Error('no cookie'));
    vi.mocked(couponsApi.fetchEvent).mockResolvedValue(event());

    renderApp('/events/1');
    await userEvent.click(await screen.findByRole('button', { name: '발급 받기' }));

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(couponsApi.requestIssue).not.toHaveBeenCalled();
  });

  it('없는 이벤트(404)면 재시도 없이 404 화면', async () => {
    vi.mocked(couponsApi.fetchEvent).mockRejectedValue(apiError(404, 'COUPON_NOT_FOUND'));

    renderApp('/events/999');

    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeInTheDocument();
    expect(screen.getByText('이벤트를 찾을 수 없어요.')).toBeInTheDocument();
    expect(couponsApi.fetchEvent).toHaveBeenCalledTimes(1);
  });

  it('숫자가 아닌 이벤트 주소(/events/abc)는 로딩에 갇히지 않고 바로 404', async () => {
    renderApp('/events/abc');

    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeInTheDocument();
    expect(couponsApi.fetchEvent).not.toHaveBeenCalled();
  });
});

describe('내 쿠폰', () => {
  beforeEach(loggedIn);

  it('받은 쿠폰을 이벤트 링크와 함께 보여 준다', async () => {
    vi.mocked(couponsApi.fetchMyCoupons).mockResolvedValue([
      { issueId: 10, couponId: 1, couponName: '오픈 기념 쿠폰', issuedAt: new Date().toISOString() },
    ]);

    renderApp('/me/coupons');

    expect(await screen.findByRole('link', { name: '오픈 기념 쿠폰' })).toHaveAttribute('href', '/events/1');
  });

  it('없으면 안내 문구', async () => {
    vi.mocked(couponsApi.fetchMyCoupons).mockResolvedValue([]);

    renderApp('/me/coupons');

    expect(await screen.findByText('아직 받은 쿠폰이 없어요.')).toBeInTheDocument();
  });
});

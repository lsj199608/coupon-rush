import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as adminApi from '../api/admin';
import type { StockReconciliation } from '../api/admin';
import * as authApi from '../api/auth';
import type { CouponEvent } from '../api/coupons';
import { tokenStore } from '../api/tokenStore';
import { apiError, renderApp } from '../test/renderApp';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
vi.mock('../api/admin', () => ({ createEvent: vi.fn(), fetchStockReconciliation: vi.fn() }));
vi.mock('../api/coupons', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/coupons')>();
  return { ...actual, fetchEvents: vi.fn().mockResolvedValue([]), fetchEvent: vi.fn() };
});

const PATH = '/admin/events/new';

function loginAs(role: 'USER' | 'ADMIN') {
  vi.mocked(authApi.restoreSession).mockImplementation(async () => {
    tokenStore.set('access-token');
    return 'access-token';
  });
  vi.mocked(authApi.fetchMe).mockResolvedValue({ id: 1, provider: 'KAKAO', nickname: '관리자', email: null, role });
}

function created(input: adminApi.CreateEventInput): CouponEvent {
  return {
    id: 42,
    ...input,
    issuedQuantity: 0,
    remainingQuantity: input.totalQuantity,
    status: 'UPCOMING',
    serverTime: new Date().toISOString(),
  };
}

function stock(overrides: Partial<StockReconciliation> = {}): StockReconciliation {
  return {
    couponId: 42,
    totalQuantity: 300,
    eventEnded: false,
    redisLoaded: true,
    redisRemaining: 300,
    redisIssuedCount: 0,
    dbIssuedCount: 0,
    dbIssuedQuantity: 0,
    problems: [],
    consistent: true,
    ...overrides,
  };
}

const field = (label: string) => screen.getByLabelText(label);

async function fill(label: string, value: string) {
  await userEvent.clear(field(label));
  if (value) await userEvent.type(field(label), value);
}

describe('관리자 화면 접근', () => {
  it('비로그인이면 로그인 화면으로', async () => {
    vi.mocked(authApi.restoreSession).mockRejectedValue(new Error('no cookie'));

    renderApp(PATH);

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
  });

  it('일반 회원이면 권한 없음 안내, 메뉴에도 관리자 링크가 없다', async () => {
    loginAs('USER');

    renderApp(PATH);

    expect(await screen.findByRole('heading', { name: '권한이 없어요' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '이벤트 만들기' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('이벤트 이름')).not.toBeInTheDocument();
  });

  it('관리자면 폼이 보이고 메뉴에 관리자 링크가 있다', async () => {
    loginAs('ADMIN');

    renderApp(PATH);

    expect(await screen.findByRole('heading', { name: '이벤트 만들기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이벤트 만들기' })).toHaveAttribute('href', PATH);
  });
});

describe('이벤트 생성 폼', () => {
  beforeEach(() => {
    loginAs('ADMIN');
    vi.mocked(adminApi.createEvent).mockImplementation(async (input) => created(input));
    vi.mocked(adminApi.fetchStockReconciliation).mockResolvedValue(stock());
  });

  async function openForm() {
    renderApp(PATH);
    await screen.findByLabelText('이벤트 이름');
  }

  it('잘못된 값이면 필드별 안내를 보여 주고 요청을 보내지 않는다', async () => {
    await openForm();
    await fill('이벤트 이름', '   ');
    await fill('총 수량', '0');
    await fill('발급 시작', '2030-01-01T10:00');
    await fill('발급 종료', '2030-01-01T09:00');

    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    expect(screen.getByText('이름을 입력해 주세요.')).toBeInTheDocument();
    expect(screen.getByText('총 수량은 1개 이상이어야 해요.')).toBeInTheDocument();
    expect(screen.getByText('종료 시각은 시작 시각보다 뒤여야 해요.')).toBeInTheDocument();
    expect(field('이벤트 이름')).toHaveAttribute('aria-invalid', 'true');
    expect(adminApi.createEvent).not.toHaveBeenCalled();
  });

  it('제출 후에는 고치는 즉시 안내가 사라진다', async () => {
    await openForm();
    await fill('이벤트 이름', '');
    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));
    expect(screen.getByText('이름을 입력해 주세요.')).toBeInTheDocument();

    await userEvent.type(field('이벤트 이름'), '블프');

    expect(screen.queryByText('이름을 입력해 주세요.')).not.toBeInTheDocument();
  });

  it('올바른 값이면 UTC ISO 시각으로 보내고, 생성 결과와 Redis 재고 적재를 확인해 보여 준다', async () => {
    await openForm();
    await fill('이벤트 이름', '  블랙프라이데이  ');
    await fill('총 수량', '300');
    await fill('발급 시작', '2030-01-01T10:00');
    await fill('발급 종료', '2030-01-01T11:30');

    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    expect(await screen.findByText(/이벤트를 만들었어요/)).toBeInTheDocument();
    expect(adminApi.createEvent).toHaveBeenCalledWith({
      name: '블랙프라이데이',
      totalQuantity: 300,
      issueStartAt: new Date('2030-01-01T10:00').toISOString(),
      issueEndAt: new Date('2030-01-01T11:30').toISOString(),
    });
    expect(await screen.findByTestId('stock-ok')).toHaveTextContent('Redis 재고 300개 적재됨');
    expect(adminApi.fetchStockReconciliation).toHaveBeenCalledWith(42);
    expect(screen.getByRole('link', { name: '이벤트 보기' })).toHaveAttribute('href', '/events/42');
  });

  it('Redis 에 재고가 없으면 경고와 sync 복구 방법을 보여 준다', async () => {
    vi.mocked(adminApi.fetchStockReconciliation).mockResolvedValue(stock({ redisLoaded: false, redisRemaining: null }));
    await openForm();
    await fill('이벤트 이름', '적재 실패 이벤트');
    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    const warning = await screen.findByTestId('stock-problem');
    expect(warning).toHaveTextContent('Redis에 재고가 적재되지 않았어요');
    expect(warning).toHaveTextContent('/api/admin/coupons/42/stock/sync');
  });

  it('서버 검증 실패(400)는 서버가 알려 준 사유를 보여 주고 입력값은 그대로 둔다', async () => {
    const error = apiError(400, 'INVALID_INPUT');
    (error.response!.data as { message: string }).message = 'totalQuantity: 1000000 이하여야 합니다';
    vi.mocked(adminApi.createEvent).mockRejectedValue(error);
    await openForm();
    await fill('이벤트 이름', '서버 거절');

    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('totalQuantity: 1000000 이하여야 합니다');
    expect(field('이벤트 이름')).toHaveValue('서버 거절');
  });

  it('권한이 없다고(403) 하면 권한 안내', async () => {
    vi.mocked(adminApi.createEvent).mockRejectedValue(apiError(403, 'FORBIDDEN'));
    await openForm();
    await fill('이벤트 이름', '권한 없음');

    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없어요.');
  });

  it('요청 중에는 버튼이 꺼져 두 번 만들어지지 않는다', async () => {
    let resolve!: (value: CouponEvent) => void;
    vi.mocked(adminApi.createEvent).mockImplementation(
      (input) => new Promise((r) => (resolve = () => r(created(input)))),
    );
    await openForm();
    await fill('이벤트 이름', '한 번만');

    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));
    const busy = screen.getByRole('button', { name: '만드는 중…' });
    expect(busy).toBeDisabled();
    await userEvent.click(busy);

    resolve(undefined as never);
    expect(await screen.findByText(/이벤트를 만들었어요/)).toBeInTheDocument();
    expect(adminApi.createEvent).toHaveBeenCalledTimes(1);
  });

  it('하나 더 만들기를 누르면 빈 폼으로 돌아간다', async () => {
    await openForm();
    await fill('이벤트 이름', '첫 번째');
    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));
    await screen.findByText(/이벤트를 만들었어요/);

    await userEvent.click(screen.getByRole('button', { name: '하나 더 만들기' }));

    await waitFor(() => expect(field('이벤트 이름')).toHaveValue(''));
  });
});

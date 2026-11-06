import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as adminApi from '../api/admin';
import type { StockReconciliation } from '../api/admin';
import * as authApi from '../api/auth';
import * as llmApi from '../api/llm';
import type { LlmRequestResult } from '../api/llm';
import { EVENT_COPY_POLLING } from '../hooks/useEventCopy';
import type { CouponEvent } from '../api/coupons';
import { tokenStore } from '../api/tokenStore';
import { apiError, renderApp } from '../test/renderApp';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
vi.mock('../api/admin', () => ({ createEvent: vi.fn(), fetchStockReconciliation: vi.fn() }));
vi.mock('../api/llm', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/llm')>();
  return { ...actual, requestEventCopy: vi.fn(), fetchLlmRequest: vi.fn() };
});
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
      description: null,
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

function llmResult(status: LlmRequestResult['status'], extra: Partial<LlmRequestResult> = {}): LlmRequestResult {
  return {
    requestId: 'llm-1',
    type: 'EVENT_COPY',
    status,
    candidates: [],
    failureReason: null,
    retryable: null,
    model: 'fake',
    inputTokens: 0,
    outputTokens: 0,
    cached: false,
    createdAt: new Date().toISOString(),
    deadlineAt: new Date(Date.now() + 60_000).toISOString(),
    completedAt: null,
    ...extra,
  };
}

const CANDIDATES = [
  { name: '[선착순 300명] 핫초코 반값', description: '딱 300명만! 놓치면 다시 없는 기회입니다.' },
  { name: '마감 임박 핫초코', description: '수량 소진 시 즉시 종료됩니다.' },
  { name: '핫초코 — 지금이 마지막 기회', description: '선착순 300명, 서두르세요.' },
];

describe('AI 문구 추천', () => {
  beforeEach(() => {
    loginAs('ADMIN');
    EVENT_COPY_POLLING.intervalMs = 20;
    EVENT_COPY_POLLING.timeoutMs = 500;
    vi.mocked(llmApi.requestEventCopy).mockResolvedValue({ requestId: 'llm-1', status: 'PENDING', deadlineAt: '' });
    vi.mocked(adminApi.createEvent).mockImplementation(async (input) => created(input));
    vi.mocked(adminApi.fetchStockReconciliation).mockResolvedValue(stock());
  });

  afterEach(() => {
    EVENT_COPY_POLLING.intervalMs = 2_000;
    EVENT_COPY_POLLING.timeoutMs = 75_000;
  });

  async function openAndGenerate(keywords = '겨울 한정 핫초코 50% 할인') {
    renderApp(PATH);
    await screen.findByLabelText('키워드');
    await fill('총 수량', '300');
    await userEvent.type(field('키워드'), keywords);
    await userEvent.selectOptions(screen.getByLabelText('톤'), 'URGENT');
    await userEvent.click(screen.getByRole('button', { name: '문구 생성' }));
  }

  it('생성 → 폴링 → 후보 3개, 고르면 이름·설명 칸을 채우고 저장은 [이벤트 만들기]를 눌러야 된다', async () => {
    vi.mocked(llmApi.fetchLlmRequest)
      .mockResolvedValueOnce(llmResult('PENDING'))
      .mockResolvedValue(llmResult('COMPLETED', { candidates: CANDIDATES }));

    await openAndGenerate();

    expect(llmApi.requestEventCopy).toHaveBeenCalledWith(
      expect.objectContaining({ keywords: '겨울 한정 핫초코 50% 할인', tone: 'URGENT', totalQuantity: 300, regenerate: false }),
    );
    const useButtons = await screen.findAllByRole('button', { name: '이 문구 쓰기' });
    expect(useButtons).toHaveLength(3);

    await userEvent.click(useButtons[1]);

    expect(field('이벤트 이름')).toHaveValue('마감 임박 핫초코');
    expect(field('설명 (선택)')).toHaveValue('수량 소진 시 즉시 종료됩니다.');
    expect(screen.getByRole('status')).toHaveTextContent('[이벤트 만들기]를 눌러야 저장돼요');
    expect(adminApi.createEvent).not.toHaveBeenCalled();   // 고르기만 해서는 저장되지 않는다

    await userEvent.type(field('설명 (선택)'), ' (1인 1회)');   // 관리자가 고친 뒤 저장
    await userEvent.click(screen.getByRole('button', { name: '이벤트 만들기' }));

    expect(await screen.findByText(/이벤트를 만들었어요/)).toBeInTheDocument();
    expect(adminApi.createEvent).toHaveBeenCalledWith(
      expect.objectContaining({ name: '마감 임박 핫초코', description: '수량 소진 시 즉시 종료됩니다. (1인 1회)' }),
    );
  });

  it('[다시 생성]은 regenerate=true 로 요청한다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(llmResult('COMPLETED', { candidates: CANDIDATES, cached: true }));
    await openAndGenerate();

    expect(await screen.findByText('같은 입력으로 만든 문구를 다시 보여 드려요.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '다시 생성' }));

    await waitFor(() => expect(llmApi.requestEventCopy).toHaveBeenLastCalledWith(expect.objectContaining({ regenerate: true })));
  });

  it('키워드가 없으면 요청하지 않고 안내한다', async () => {
    renderApp(PATH);
    await userEvent.click(await screen.findByRole('button', { name: '문구 생성' }));

    expect(screen.getByText(/키워드를 입력해 주세요/)).toBeInTheDocument();
    expect(llmApi.requestEventCopy).not.toHaveBeenCalled();
  });

  it('분당 한도(429)는 안내와 [다시 시도], 예산 소진은 [다시 시도] 없이 안내만', async () => {
    vi.mocked(llmApi.requestEventCopy).mockRejectedValueOnce(apiError(429, 'LLM_RATE_LIMITED'));
    await openAndGenerate();

    expect(await screen.findByRole('alert')).toHaveTextContent('1분에 5번까지');

    vi.mocked(llmApi.requestEventCopy).mockRejectedValueOnce(apiError(429, 'LLM_BUDGET_EXCEEDED'));
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('예산을 모두 썼어요'));
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
  });

  it('입력을 바꿔야 하는 실패(거절)는 사유를 안내하고 [다시 시도]가 없다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(
      llmResult('FAILED', { failureReason: 'CONTENT_REFUSED', retryable: false }),
    );
    await openAndGenerate();

    expect(await screen.findByRole('alert')).toHaveTextContent('키워드를 바꿔 주세요');
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
  });

  it('LLM 장애처럼 다시 시도할 수 있는 실패는 [다시 시도]가 있다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(
      llmResult('FAILED', { failureReason: 'LLM_UNAVAILABLE', retryable: true }),
    );
    await openAndGenerate();

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 원활하지 않아요');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('결과가 너무 늦으면 폴링을 멈추고 안내한다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(llmResult('PENDING'));
    await openAndGenerate();

    expect(await screen.findByRole('alert', {}, { timeout: 2_000 })).toHaveTextContent('너무 오래 걸려');
  });

  it('생성 중에는 버튼이 꺼져 두 번 요청되지 않는다', async () => {
    vi.mocked(llmApi.fetchLlmRequest).mockResolvedValue(llmResult('PENDING'));
    await openAndGenerate();

    const busy = await screen.findByRole('button', { name: '만드는 중…' });
    expect(busy).toBeDisabled();
    await userEvent.click(busy);
    expect(llmApi.requestEventCopy).toHaveBeenCalledTimes(1);
  });
});

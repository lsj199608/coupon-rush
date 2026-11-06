import { describe, expect, it } from 'vitest';
import {
  defaultEventFormValues,
  toCreateEventInput,
  toEventCopyInput,
  toLocalInputValue,
  validateEventCopyRequest,
  validateEventForm,
  type EventFormValues,
} from './eventForm';

const NOW = new Date('2030-01-01T09:00').getTime();

function values(overrides: Partial<EventFormValues> = {}): EventFormValues {
  return {
    name: '블프',
    description: '',
    totalQuantity: '100',
    issueStartAt: '2030-01-01T10:00',
    issueEndAt: '2030-01-01T11:00',
    ...overrides,
  };
}

describe('이벤트 폼 검증 (백엔드 CouponCreateRequest 와 같은 규칙)', () => {
  it('올바른 값이면 에러 없음', () => {
    expect(validateEventForm(values(), NOW)).toEqual({});
  });

  it.each([
    ['', '이름을 입력해 주세요.'],
    ['    ', '이름을 입력해 주세요.'],
    ['가'.repeat(101), '이름은 100자 이하로 입력해 주세요.'],
  ])('이름 %j → %s', (name, message) => {
    expect(validateEventForm(values({ name }), NOW).name).toBe(message);
  });

  it('이름 100자는 통과 (경계값)', () => {
    expect(validateEventForm(values({ name: '가'.repeat(100) }), NOW).name).toBeUndefined();
  });

  it.each([
    ['', '총 수량을 입력해 주세요.'],
    ['0', '총 수량은 1개 이상이어야 해요.'],
    ['-5', '총 수량은 정수로 입력해 주세요.'],
    ['1.5', '총 수량은 정수로 입력해 주세요.'],
    ['1e3', '총 수량은 정수로 입력해 주세요.'],
    ['1000001', '총 수량은 1,000,000개 이하로 입력해 주세요.'],
  ])('총 수량 %j → %s', (totalQuantity, message) => {
    expect(validateEventForm(values({ totalQuantity }), NOW).totalQuantity).toBe(message);
  });

  it('총 수량 1 과 1,000,000 은 통과 (경계값)', () => {
    expect(validateEventForm(values({ totalQuantity: '1' }), NOW).totalQuantity).toBeUndefined();
    expect(validateEventForm(values({ totalQuantity: '1000000' }), NOW).totalQuantity).toBeUndefined();
  });

  it('시각이 비었거나, 종료가 시작보다 앞이거나 같거나, 이미 지났으면 막는다', () => {
    expect(validateEventForm(values({ issueStartAt: '' }), NOW).issueStartAt).toBe('시작 시각을 입력해 주세요.');
    expect(validateEventForm(values({ issueEndAt: '' }), NOW).issueEndAt).toBe('종료 시각을 입력해 주세요.');
    expect(validateEventForm(values({ issueEndAt: '2030-01-01T10:00' }), NOW).issueEndAt).toBe('종료 시각은 시작 시각보다 뒤여야 해요.');
    expect(
      validateEventForm(values({ issueStartAt: '2029-12-31T07:00', issueEndAt: '2029-12-31T08:00' }), NOW).issueEndAt,
    ).toBe('종료 시각이 이미 지났어요.');
  });

  it('이미 시작한(시작 시각이 과거인) 이벤트는 허용 — 바로 여는 이벤트', () => {
    expect(validateEventForm(values({ issueStartAt: '2030-01-01T08:00' }), NOW)).toEqual({});
  });
});

describe('폼 값 → API 바디', () => {
  it('이름을 다듬고 수량은 숫자로, 로컬 시각은 UTC ISO 로', () => {
    expect(toCreateEventInput(values({ name: '  블프 ' }))).toEqual({
      name: '블프',
      description: null,
      totalQuantity: 100,
      issueStartAt: new Date('2030-01-01T10:00').toISOString(),
      issueEndAt: new Date('2030-01-01T11:00').toISOString(),
    });
  });

  it('기본값은 5분 뒤 분 단위 시작, 1시간 진행', () => {
    const defaults = defaultEventFormValues(new Date('2030-01-01T09:00:30').getTime());
    expect(defaults.issueStartAt).toBe('2030-01-01T09:06');
    expect(defaults.issueEndAt).toBe('2030-01-01T10:06');
    expect(toLocalInputValue(new Date('2030-03-04T05:06').getTime())).toBe('2030-03-04T05:06');
  });
});

describe('설명 (선택)', () => {
  it('앞뒤 공백을 지워 보내고, 비어 있으면 null', () => {
    expect(toCreateEventInput(values({ description: '  12월 1일 10시  ' })).description).toBe('12월 1일 10시');
    expect(toCreateEventInput(values({ description: '   ' })).description).toBeNull();
  });

  it('500자까지 허용하고 넘으면 안내한다', () => {
    expect(validateEventForm(values({ description: '가'.repeat(500) }), NOW).description).toBeUndefined();
    expect(validateEventForm(values({ description: '가'.repeat(501) }), NOW).description).toBe('설명은 500자 이하로 입력해 주세요.');
  });
});

describe('문구 생성 요청', () => {
  it('키워드가 없거나 너무 길면, 또는 수량이 잘못됐으면 막는다', () => {
    expect(validateEventCopyRequest('  ', values())).toMatch(/키워드를 입력/);
    expect(validateEventCopyRequest('가'.repeat(201), values())).toMatch(/200자/);
    expect(validateEventCopyRequest('겨울', values({ totalQuantity: '0' }))).toMatch(/총 수량/);
    expect(validateEventCopyRequest('겨울', values())).toBeNull();
  });

  it('폼의 수량과 (올바를 때만) 기간을 함께 보낸다', () => {
    expect(toEventCopyInput(' 겨울 할인 ', 'URGENT', values(), false)).toEqual({
      keywords: '겨울 할인',
      tone: 'URGENT',
      totalQuantity: 100,
      issueStartAt: new Date('2030-01-01T10:00').toISOString(),
      issueEndAt: new Date('2030-01-01T11:00').toISOString(),
      regenerate: false,
    });
    const reversed = toEventCopyInput('겨울', 'FRIENDLY', values({ issueEndAt: '2030-01-01T09:00' }), true);
    expect(reversed).not.toHaveProperty('issueStartAt');
    expect(reversed.regenerate).toBe(true);
  });
});

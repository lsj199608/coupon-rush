import type { CreateEventInput } from '../api/admin';

/** 폼 입력값 그대로 (input 은 문자열) */
export interface EventFormValues {
  name: string;
  totalQuantity: string;
  /** <input type="datetime-local"> 값: "2026-11-05T19:00" (내 PC 시간대 기준) */
  issueStartAt: string;
  issueEndAt: string;
}

export type EventFormErrors = Partial<Record<keyof EventFormValues, string>>;

/** 백엔드 CouponCreateRequest 의 제약과 같게 맞춘다 (@Size(max=100), @Positive @Max(1_000_000), 시작 < 종료) */
export const EVENT_LIMITS = { nameMax: 100, quantityMax: 1_000_000 } as const;

/**
 * 클라이언트 검증. 서버도 같은 규칙으로 다시 검증하므로(최종 판정) 여기는 빠른 안내용이다.
 * 서버에 없는 규칙은 하나 — 종료 시각이 이미 지났으면 막는다 (만들자마자 종료된 이벤트는 의미가 없어서).
 */
export function validateEventForm(values: EventFormValues, nowMs: number): EventFormErrors {
  const errors: EventFormErrors = {};

  const name = values.name.trim();
  if (!name) errors.name = '이름을 입력해 주세요.';
  else if (name.length > EVENT_LIMITS.nameMax) errors.name = `이름은 ${EVENT_LIMITS.nameMax}자 이하로 입력해 주세요.`;

  const quantityText = values.totalQuantity.trim();
  const quantity = Number(quantityText);
  if (!quantityText) errors.totalQuantity = '총 수량을 입력해 주세요.';
  else if (!/^\d+$/.test(quantityText) || !Number.isSafeInteger(quantity)) errors.totalQuantity = '총 수량은 정수로 입력해 주세요.';
  else if (quantity < 1) errors.totalQuantity = '총 수량은 1개 이상이어야 해요.';
  else if (quantity > EVENT_LIMITS.quantityMax)
    errors.totalQuantity = `총 수량은 ${EVENT_LIMITS.quantityMax.toLocaleString()}개 이하로 입력해 주세요.`;

  const start = parseLocal(values.issueStartAt);
  const end = parseLocal(values.issueEndAt);
  if (!values.issueStartAt) errors.issueStartAt = '시작 시각을 입력해 주세요.';
  else if (start === null) errors.issueStartAt = '시작 시각 형식이 올바르지 않아요.';
  if (!values.issueEndAt) errors.issueEndAt = '종료 시각을 입력해 주세요.';
  else if (end === null) errors.issueEndAt = '종료 시각 형식이 올바르지 않아요.';
  else if (start !== null && end <= start) errors.issueEndAt = '종료 시각은 시작 시각보다 뒤여야 해요.';
  else if (end <= nowMs) errors.issueEndAt = '종료 시각이 이미 지났어요.';

  return errors;
}

/** 검증을 통과한 값 → API 바디. 로컬 시각을 UTC ISO 로 바꿔 보낸다 (서버는 Instant 로 받음) */
export function toCreateEventInput(values: EventFormValues): CreateEventInput {
  return {
    name: values.name.trim(),
    totalQuantity: Number(values.totalQuantity.trim()),
    issueStartAt: new Date(values.issueStartAt).toISOString(),
    issueEndAt: new Date(values.issueEndAt).toISOString(),
  };
}

/** epoch ms → datetime-local 입력값 (내 PC 시간대, 분 단위) */
export function toLocalInputValue(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 기본값: 5분 뒤 정각 분에 시작, 1시간 진행 */
export function defaultEventFormValues(nowMs: number): EventFormValues {
  const start = Math.ceil((nowMs + 5 * 60_000) / 60_000) * 60_000;
  return {
    name: '',
    totalQuantity: '100',
    issueStartAt: toLocalInputValue(start),
    issueEndAt: toLocalInputValue(start + 60 * 60_000),
  };
}

function parseLocal(value: string): number | null {
  if (!value) return null;
  const ms = new Date(value).getTime();
  return Number.isNaN(ms) ? null : ms;
}

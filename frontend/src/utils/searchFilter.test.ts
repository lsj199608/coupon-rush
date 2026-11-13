import { describe, expect, it } from 'vitest';
import type { EventSearchFilter } from '../api/search';
import { describeFilter } from './searchFilter';

/** PC 시간대 0시 (테스트가 어느 시간대에서 돌아도 같은 날짜로 보이게 — 서버는 한국 0시를 보낸다) */
const midnight = (month: number, day: number) => new Date(2026, month - 1, day).toISOString();

function filter(overrides: Partial<EventSearchFilter> = {}): EventSearchFilter {
  return { keywords: [], statuses: [], startFrom: null, startTo: null, endFrom: null, endTo: null, availableOnly: false, ...overrides };
}

describe('describeFilter', () => {
  it('조건이 없으면 빈 목록', () => {
    expect(describeFilter(filter())).toEqual([]);
  });

  it('단어, 상태(여러 개면 "또는"), 남은 수량', () => {
    expect(describeFilter(filter({ keywords: ['커피', '할인'], statuses: ['OPEN', 'UPCOMING'], availableOnly: true })))
      .toEqual(['"커피"', '"할인"', '진행 중 또는 오픈 전', '남은 수량 있음']);
  });

  it('기간의 끝(to)은 포함하지 않으므로 그 전날까지로 보여 준다', () => {
    // "이번 주에 끝나는" = 월요일 0시 ~ 다음 월요일 0시
    expect(describeFilter(filter({ endFrom: midnight(11, 9), endTo: midnight(11, 16) }))).toEqual(['종료 11월 9일 ~ 11월 15일']);
  });

  it('하루짜리 범위는 날짜 하나, 한쪽만 있으면 이후 / 까지', () => {
    expect(describeFilter(filter({ startFrom: midnight(11, 14), startTo: midnight(11, 15) }))).toEqual(['시작 11월 14일']);
    expect(describeFilter(filter({ startFrom: midnight(11, 14) }))).toEqual(['시작 11월 14일 이후']);
    expect(describeFilter(filter({ endTo: midnight(11, 16) }))).toEqual(['종료 11월 15일까지']);
  });
});

import type { EventSearchFilter } from '../api/search';
import { STATUS_LABELS } from '../components/StatusBadge';

const monthDay = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });

/**
 * 서버 범위 [from, to) → 사람이 읽는 날짜 범위 (양끝 포함). to 는 "그날 0시 미포함"이라 1ms 앞의 날짜로 보여 준다
 * ("이번 주에 끝나는" = endTo 다음 주 월요일 0시 → "일요일까지").
 */
function range(label: string, from: string | null, to: string | null): string | null {
  if (from === null && to === null) return null;
  const start = from !== null ? monthDay.format(new Date(from)) : null;
  const end = to !== null ? monthDay.format(new Date(new Date(to).getTime() - 1)) : null;
  if (start !== null && end !== null) {
    return start === end ? `${label} ${start}` : `${label} ${start} ~ ${end}`;
  }
  return start !== null ? `${label} ${start} 이후` : `${label} ${end}까지`;
}

/** AI 가 만든 조건 → 화면에 보여 줄 짧은 설명 목록 ("이렇게 찾았어요"). 조건이 없으면 빈 목록 */
export function describeFilter(filter: EventSearchFilter): string[] {
  return [
    ...filter.keywords.map((keyword) => `"${keyword}"`),
    ...(filter.statuses.length > 0 ? [filter.statuses.map((status) => STATUS_LABELS[status]).join(' 또는 ')] : []),
    range('시작', filter.startFrom, filter.startTo),
    range('종료', filter.endFrom, filter.endTo),
    filter.availableOnly ? '남은 수량 있음' : null,
  ].filter((item): item is string => item !== null);
}

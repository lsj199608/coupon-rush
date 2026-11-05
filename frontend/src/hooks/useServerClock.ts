import { useEffect, useState } from 'react';
import type { CouponEvent, EventStatus } from '../api/coupons';

/**
 * 서버 시각에 맞춘 "지금". 응답의 serverTime 과 내 PC 시계의 차이(offset)를 보정한다.
 * → 사용자 PC 시계가 몇 분 틀려도 카운트다운과 버튼 활성화가 서버 오픈 시각에 맞는다.
 * (네트워크 지연만큼은 오차가 남지만 수십 ms 수준. 최종 판정은 어차피 서버가 한다)
 *
 * @param serverTime 응답의 serverTime (없으면 보정 없이 내 시계)
 * @param tickMs     다시 그리는 간격
 */
export function useServerNow(serverTime: string | undefined, tickMs = 1_000): number {
  const [offset, setOffset] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (serverTime) {
      setOffset(new Date(serverTime).getTime() - Date.now());
    }
  }, [serverTime]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), tickMs);
    return () => clearInterval(timer);
  }, [tickMs]);

  return now + offset;
}

/** 서버 기준 지금 시각으로 상태를 다시 계산 (응답 이후 시간이 흘러 오픈/종료됐을 수 있으므로) */
export function statusAt(event: Pick<CouponEvent, 'issueStartAt' | 'issueEndAt'>, nowMs: number): EventStatus {
  if (nowMs < new Date(event.issueStartAt).getTime()) return 'UPCOMING';
  if (nowMs < new Date(event.issueEndAt).getTime()) return 'OPEN';
  return 'ENDED';
}

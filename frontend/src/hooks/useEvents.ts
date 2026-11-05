import { useQuery } from '@tanstack/react-query';
import { fetchEvent, fetchEvents } from '../api/coupons';
import { errorCodeOf } from '../api/errors';
import { queryKeys } from '../api/queryKeys';

export function useEventsQuery() {
  return useQuery({
    queryKey: queryKeys.events,
    queryFn: fetchEvents,
  });
}

/**
 * 이벤트 상세. 진행 중일 때는 남은 수량이 계속 바뀌므로 3초마다 다시 가져온다.
 * (오픈 전/종료는 수량이 바뀌지 않으니 폴링하지 않는다)
 */
export function useEventQuery(id: number, { live }: { live: boolean }) {
  return useQuery({
    queryKey: queryKeys.event(id),
    queryFn: () => fetchEvent(id),
    enabled: Number.isSafeInteger(id) && id > 0,
    refetchInterval: live ? 3_000 : false,
    // 없는 이벤트(404)는 다시 불러도 같으므로 재시도하지 않는다
    retry: (failureCount, error) => errorCodeOf(error) !== 'COUPON_NOT_FOUND' && failureCount < 1,
  });
}

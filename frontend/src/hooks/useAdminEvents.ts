import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createEvent, fetchStockReconciliation } from '../api/admin';
import { queryKeys } from '../api/queryKeys';

/** 이벤트 생성. 성공하면 이벤트 목록 캐시를 무효화해 새 이벤트가 바로 보이게 한다 */
export function useCreateEventMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createEvent,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.events }),
  });
}

/** 생성 직후 Redis 재고가 적재됐는지 확인 (대조 API). couponId 가 있을 때만 호출 */
export function useStockCheckQuery(couponId: number | undefined) {
  return useQuery({
    queryKey: queryKeys.stockCheck(couponId ?? -1),
    queryFn: () => fetchStockReconciliation(couponId!),
    enabled: couponId !== undefined,
    staleTime: 0,
  });
}

import { useQuery } from '@tanstack/react-query';
import { fetchMyCoupons } from '../api/coupons';
import { queryKeys } from '../api/queryKeys';

export function useMyCouponsQuery() {
  return useQuery({
    queryKey: queryKeys.myCoupons,
    queryFn: fetchMyCoupons,
  });
}

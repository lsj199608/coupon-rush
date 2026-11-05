import { Link } from 'react-router-dom';
import { QueryState } from '../components/QueryState';
import { useMyCouponsQuery } from '../hooks/useMyCoupons';
import { eventPath } from '../routes';
import { formatDateTime } from '../utils/format';

export function MyCouponsPage() {
  const query = useMyCouponsQuery();

  return (
    <section>
      <h1>내 쿠폰</h1>
      <QueryState query={query} loadingLabel="쿠폰을 불러오는 중…" emptyMessage="아직 받은 쿠폰이 없어요.">
        {(coupons) => (
          <ul className="coupon-list">
            {coupons.map((coupon) => (
              <li key={coupon.issueId} className="coupon-card">
                <Link to={eventPath(coupon.couponId)}>
                  <strong>{coupon.couponName}</strong>
                </Link>
                <span className="muted">{formatDateTime(coupon.issuedAt)} 발급</span>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </section>
  );
}

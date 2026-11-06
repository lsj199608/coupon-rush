import { useParams } from 'react-router-dom';
import type { CouponEvent } from '../api/coupons';
import { errorCodeOf } from '../api/errors';
import { NotFoundPage } from './NotFoundPage';
import { IssuePanel } from '../components/IssuePanel';
import { QueryState } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { useEventQuery } from '../hooks/useEvents';
import { statusAt, useServerNow } from '../hooks/useServerClock';
import { formatCountdown, formatDateTime } from '../utils/format';

export function EventDetailPage() {
  const id = Number(useParams().id);
  const validId = Number.isSafeInteger(id) && id > 0;
  const query = useEventQuery(id, { live: validId });

  // /events/abc 같은 주소는 쿼리가 꺼져(enabled=false) 영원히 로딩으로 남으므로 먼저 404로 돌린다.
  // 없는 이벤트(404)도 [다시 시도]가 의미 없으므로 같은 404 화면.
  if (!validId || errorCodeOf(query.error) === 'COUPON_NOT_FOUND') {
    return <NotFoundPage message="이벤트를 찾을 수 없어요." />;
  }

  return (
    <section>
      <QueryState query={query} loadingLabel="이벤트를 불러오는 중…">
        {(event) => <EventDetail event={event} />}
      </QueryState>
    </section>
  );
}

function EventDetail({ event }: { event: CouponEvent }) {
  // 응답 이후에도 시간이 흐르므로 상태는 서버 시계 기준 "지금"으로 다시 계산한다 → 오픈 순간 버튼이 바로 켜진다
  const now = useServerNow(event.serverTime);
  const status = statusAt(event, now);
  const untilOpen = new Date(event.issueStartAt).getTime() - now;

  return (
    <>
      <div className="detail-head">
        <StatusBadge status={status} />
        <h1>{event.name}</h1>
        {event.description && <p className="event-description">{event.description}</p>}
      </div>
      <dl className="detail-info">
        <dt>발급 기간</dt>
        <dd>
          {formatDateTime(event.issueStartAt)} ~ {formatDateTime(event.issueEndAt)}
        </dd>
        <dt>남은 수량</dt>
        <dd>
          <strong>{event.remainingQuantity.toLocaleString()}</strong> / {event.totalQuantity.toLocaleString()}
        </dd>
      </dl>
      {status === 'UPCOMING' && (
        <p className="countdown" aria-live="polite">
          오픈까지 <strong>{formatCountdown(untilOpen)}</strong>
        </p>
      )}
      <IssuePanel couponId={event.id} status={status} remainingQuantity={event.remainingQuantity} />
    </>
  );
}

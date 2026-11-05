import { Link } from 'react-router-dom';
import { QueryState } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { useEventsQuery } from '../hooks/useEvents';
import { eventPath } from '../routes';
import { formatDateTime } from '../utils/format';

export function EventListPage() {
  const query = useEventsQuery();

  return (
    <section>
      <h1>이벤트</h1>
      <QueryState query={query} loadingLabel="이벤트를 불러오는 중…" emptyMessage="진행 중인 이벤트가 없어요.">
        {(events) => (
          <ul className="event-list">
            {events.map((event) => (
              <li key={event.id}>
                <Link to={eventPath(event.id)} className="event-card">
                  <div className="event-card-head">
                    <StatusBadge status={event.status} />
                    <strong>{event.name}</strong>
                  </div>
                  <p className="muted">
                    {formatDateTime(event.issueStartAt)} ~ {formatDateTime(event.issueEndAt)}
                  </p>
                  <p className="muted">
                    남은 수량 {event.remainingQuantity.toLocaleString()} / {event.totalQuantity.toLocaleString()}
                  </p>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </section>
  );
}

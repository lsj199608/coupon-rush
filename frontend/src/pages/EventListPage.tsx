import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { CouponEvent } from '../api/coupons';
import { errorMessageOf, searchFallbackMessage } from '../api/errors';
import type { EventSearchResult } from '../api/search';
import { QueryState } from '../components/QueryState';
import { Spinner } from '../components/Spinner';
import { StatusBadge } from '../components/StatusBadge';
import { useEventsQuery } from '../hooks/useEvents';
import { useEventSearch } from '../hooks/useEventSearch';
import { eventPath } from '../routes';
import { formatDateTime } from '../utils/format';
import { describeFilter } from '../utils/searchFilter';

/** 백엔드 EventSearchRequest 상한 (계약 EventSearchInput.QUERY_MAX) */
const QUERY_MAX = 200;

/**
 * 이벤트 목록 + 자연어 검색. 검색어는 URL(?q=)에 둔다 — 상세에 갔다가 뒤로 와도, 링크를 공유해도 같은 검색이 보인다.
 * 검색어가 없으면 전체 목록(GET /api/coupons), 있으면 검색 결과(useEventSearch)를 같은 카드로 보여 준다.
 */
export function EventListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get('q')?.trim() || null;

  return (
    <section>
      <h1>이벤트</h1>
      <SearchForm query={query} onSearch={(next) => setSearchParams(next ? { q: next } : {})} />
      {query === null ? <AllEvents /> : <SearchResults query={query} />}
    </section>
  );
}

function SearchForm({ query, onSearch }: { query: string | null; onSearch: (query: string | null) => void }) {
  const [draft, setDraft] = useState(query ?? '');
  // 뒤로 가기 등으로 URL 의 검색어가 바뀌면 입력칸도 따라간다
  useEffect(() => setDraft(query ?? ''), [query]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const next = draft.trim().replace(/\s+/g, ' ');
    onSearch(next === '' ? null : next);
  };

  return (
    <form className="search-form" role="search" onSubmit={submit}>
      <input
        type="search"
        aria-label="이벤트 검색"
        placeholder="예: 이번 주에 끝나는 커피 쿠폰"
        maxLength={QUERY_MAX}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
      />
      <button type="submit" className="primary">
        검색
      </button>
      {query !== null && (
        <button type="button" onClick={() => onSearch(null)}>
          전체 보기
        </button>
      )}
    </form>
  );
}

function AllEvents() {
  const query = useEventsQuery();
  return (
    <QueryState query={query} loadingLabel="이벤트를 불러오는 중…" emptyMessage="진행 중인 이벤트가 없어요.">
      {(events) => <EventList events={events} />}
    </QueryState>
  );
}

function SearchResults({ query }: { query: string }) {
  const { state, retry } = useEventSearch(query);

  switch (state.phase) {
    case 'idle':
    case 'searching':
      return <Spinner label="AI가 검색 조건을 만드는 중…" />;
    case 'timeout':
    case 'error':
      return (
        <div className="notice notice-error" role="alert">
          <p>{state.phase === 'timeout' ? '검색이 너무 오래 걸려요. 다시 검색해 주세요.' : errorMessageOf(state.error)}</p>
          <button type="button" onClick={retry}>
            다시 검색
          </button>
        </div>
      );
    case 'done':
      return (
        <>
          <SearchSummary result={state.result} />
          {state.result.events.length === 0 ? (
            <p className="muted">조건에 맞는 이벤트가 없어요.</p>
          ) : (
            <EventList events={state.result.events} />
          )}
        </>
      );
  }
}

/** 어떻게 찾았는지 — AI 조건이면 조건 목록, 키워드 검색이면 이유와 단어 */
function SearchSummary({ result }: { result: EventSearchResult }) {
  if (result.status === 'COMPLETED' && result.filter !== null) {
    const conditions = describeFilter(result.filter);
    return (
      <div className="search-summary" aria-label="검색 조건">
        {conditions.length === 0 ? (
          <p className="muted">조건 없이 전체 이벤트를 보여 드려요.</p>
        ) : (
          <>
            <span className="muted">AI가 이렇게 찾았어요</span>
            <ul className="chips">
              {conditions.map((condition) => (
                <li key={condition}>{condition}</li>
              ))}
            </ul>
          </>
        )}
      </div>
    );
  }
  return (
    <div className="search-summary notice notice-warn" role="status" aria-label="검색 조건">
      <p>{searchFallbackMessage(result.fallbackReason)}</p>
      {result.keywords.length > 0 ? (
        <ul className="chips">
          {result.keywords.map((keyword) => (
            <li key={keyword}>"{keyword}"</li>
          ))}
        </ul>
      ) : (
        <p className="muted">찾을 단어가 없어 전체 이벤트를 보여 드려요.</p>
      )}
    </div>
  );
}

function EventList({ events }: { events: CouponEvent[] }) {
  return (
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
  );
}

import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import type { CouponEvent } from '../api/coupons';
import { errorMessageOf, formErrorMessageOf } from '../api/errors';
import { EventCopyPanel } from '../components/EventCopyPanel';
import { Spinner } from '../components/Spinner';
import { useCreateEventMutation, useStockCheckQuery } from '../hooks/useAdminEvents';
import { eventPath } from '../routes';
import { formatDateTime } from '../utils/format';
import {
  EVENT_LIMITS,
  defaultEventFormValues,
  toCreateEventInput,
  validateEventForm,
  type EventFormErrors,
  type EventFormValues,
} from '../utils/eventForm';

const FIELDS: { key: keyof EventFormValues; label: string }[] = [
  { key: 'name', label: '이벤트 이름' },
  { key: 'description', label: '설명 (선택)' },
  { key: 'totalQuantity', label: '총 수량' },
  { key: 'issueStartAt', label: '발급 시작' },
  { key: 'issueEndAt', label: '발급 종료' },
];

/**
 * 관리자: 이벤트(쿠폰) 생성. 폼 입력값은 화면 상태(useState), 서버 결과는 useMutation 으로만 다룬다.
 * 검증은 제출할 때 한 번, 그 뒤로는 입력이 바뀔 때마다 다시 한다 (처음부터 빨간 글씨를 띄우지 않기 위해).
 */
export function AdminEventCreatePage() {
  const [values, setValues] = useState<EventFormValues>(() => defaultEventFormValues(Date.now()));
  const [errors, setErrors] = useState<EventFormErrors>({});
  const [submitted, setSubmitted] = useState(false);
  const mutation = useCreateEventMutation();

  const update = (patch: Partial<EventFormValues>) => {
    const next = { ...values, ...patch };
    setValues(next);
    if (submitted) setErrors(validateEventForm(next, Date.now()));
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (mutation.isPending) return;
    setSubmitted(true);
    const found = validateEventForm(values, Date.now());
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    mutation.mutate(toCreateEventInput(values));
  };

  const startNew = () => {
    mutation.reset();
    setSubmitted(false);
    setErrors({});
    setValues(defaultEventFormValues(Date.now()));
  };

  if (mutation.isSuccess) {
    return <CreatedEvent event={mutation.data} onCreateAnother={startNew} />;
  }

  return (
    <section>
      <h1>이벤트 만들기</h1>
      <EventCopyPanel
        values={values}
        onApply={(candidate) => update({ name: candidate.name, description: candidate.description })}
      />
      <form className="form" onSubmit={handleSubmit} noValidate>
        {FIELDS.map(({ key, label }) => (
          <div key={key} className="field">
            <label htmlFor={`event-${key}`}>{label}</label>
            {key === 'description' ? (
              <textarea
                id={`event-${key}`}
                rows={3}
                maxLength={EVENT_LIMITS.descriptionMax}
                value={values[key]}
                onChange={(e) => update({ [key]: e.target.value })}
                aria-invalid={errors[key] ? true : undefined}
                aria-describedby={errors[key] ? `event-${key}-error` : undefined}
              />
            ) : (
              <input
                id={`event-${key}`}
                {...inputProps(key)}
                value={values[key]}
                onChange={(e) => update({ [key]: e.target.value })}
                aria-invalid={errors[key] ? true : undefined}
                aria-describedby={errors[key] ? `event-${key}-error` : undefined}
              />
            )}
            {errors[key] && (
              <p id={`event-${key}-error`} className="field-error">
                {errors[key]}
              </p>
            )}
          </div>
        ))}
        <p className="muted hint">시각은 이 PC의 시간대 기준이에요. 서버에는 UTC로 바꿔 보냅니다.</p>
        {mutation.isError && (
          <p className="notice notice-error" role="alert">
            {formErrorMessageOf(mutation.error)}
          </p>
        )}
        <button type="submit" className="primary" disabled={mutation.isPending}>
          {mutation.isPending ? '만드는 중…' : '이벤트 만들기'}
        </button>
      </form>
    </section>
  );
}

function inputProps(key: keyof EventFormValues) {
  switch (key) {
    case 'name':
      return { type: 'text', maxLength: EVENT_LIMITS.nameMax, autoComplete: 'off' };
    case 'totalQuantity':
      return { type: 'number', inputMode: 'numeric' as const, min: 1, max: EVENT_LIMITS.quantityMax, step: 1 };
    default:
      return { type: 'datetime-local' };
  }
}

/** 생성 완료 + Redis 재고 적재 확인 */
function CreatedEvent({ event, onCreateAnother }: { event: CouponEvent; onCreateAnother: () => void }) {
  const stock = useStockCheckQuery(event.id);

  return (
    <section>
      <div className="notice notice-success" role="status">
        <p>
          <strong>{event.name}</strong> 이벤트를 만들었어요. (ID {event.id})
        </p>
        {event.description && <p className="event-description">{event.description}</p>}
        <p className="muted">
          {formatDateTime(event.issueStartAt)} ~ {formatDateTime(event.issueEndAt)} · 총 {event.totalQuantity.toLocaleString()}개
        </p>
      </div>

      <h2>Redis 재고 확인</h2>
      {stock.isPending && <Spinner label="Redis 재고를 확인하는 중…" />}
      {stock.isError && (
        <div className="notice notice-error" role="alert">
          <p>{errorMessageOf(stock.error)}</p>
          <button type="button" onClick={() => void stock.refetch()}>
            다시 확인
          </button>
        </div>
      )}
      {stock.isSuccess &&
        (stock.data.redisLoaded && stock.data.redisRemaining === event.totalQuantity ? (
          <p className="notice notice-success" data-testid="stock-ok">
            Redis 재고 {stock.data.redisRemaining?.toLocaleString()}개 적재됨 ✓
          </p>
        ) : (
          <div className="notice notice-warn" data-testid="stock-problem">
            <p>
              {stock.data.redisLoaded
                ? `Redis 재고(${stock.data.redisRemaining})가 총 수량과 달라요.`
                : 'Redis에 재고가 적재되지 않았어요. 이대로는 발급 시 "준비 중"(503)이 나와요.'}
            </p>
            <p className="muted">복구: POST /api/admin/coupons/{event.id}/stock/sync</p>
            <button type="button" onClick={() => void stock.refetch()}>
              다시 확인
            </button>
          </div>
        ))}

      <div className="actions">
        <Link to={eventPath(event.id)} className="button-link">
          이벤트 보기
        </Link>
        <button type="button" onClick={onCreateAnother}>
          하나 더 만들기
        </button>
      </div>
    </section>
  );
}

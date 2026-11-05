import { Link, useLocation, useNavigate } from 'react-router-dom';
import type { EventStatus } from '../api/coupons';
import { errorMessageOf, failureReasonMessage, isFinalRejection } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import { useIssueCoupon, type IssuePhase } from '../hooks/useIssueCoupon';
import { ROUTES } from '../routes';
import { Spinner } from './Spinner';

interface IssuePanelProps {
  couponId: number;
  /** 서버 시각 기준으로 다시 계산한 지금 상태 */
  status: EventStatus;
  remainingQuantity: number;
}

/** [발급 받기] 버튼 + 결과 안내 */
export function IssuePanel({ couponId, status, remainingQuantity }: IssuePanelProps) {
  const { status: authStatus } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { state, issue, reset } = useIssueCoupon(couponId);

  const busy = state.phase === 'submitting' || state.phase === 'pending';
  const done =
    state.phase === 'completed' ||
    state.phase === 'timeout' ||
    (state.phase === 'rejected' && isFinalRejection(state.code)) ||
    (state.phase === 'failed' && state.reason === 'COUPON_ALREADY_ISSUED');
  const soldOutBeforeClick = state.phase === 'idle' && remainingQuantity <= 0;
  const disabled = status !== 'OPEN' || busy || done || soldOutBeforeClick;

  const handleClick = () => {
    if (authStatus !== 'authenticated') {
      navigate(ROUTES.login, { state: { from: location.pathname } });
      return;
    }
    issue();
  };

  return (
    <div className="issue-panel">
      <button type="button" className="primary issue-button" disabled={disabled} onClick={handleClick}>
        {buttonLabel(status, state, soldOutBeforeClick)}
      </button>
      <IssueResult state={state} onRetry={reset} />
    </div>
  );
}

function buttonLabel(status: EventStatus, state: IssuePhase, soldOut: boolean): string {
  if (status === 'UPCOMING') return '오픈 전';
  if (status === 'ENDED') return '종료된 이벤트';
  if (soldOut) return '소진';
  switch (state.phase) {
    case 'submitting':
      return '요청 중…';
    case 'pending':
      return '처리 중…';
    case 'completed':
      return '발급 완료';
    default:
      return '발급 받기';
  }
}

function IssueResult({ state, onRetry }: { state: IssuePhase; onRetry: () => void }) {
  switch (state.phase) {
    case 'idle':
      return null;
    case 'submitting':
      return <Spinner label="발급 요청을 보내는 중…" />;
    case 'pending':
      return <Spinner label="선착순 접수 완료! 발급을 처리하고 있어요…" />;
    case 'completed':
      return (
        <div className="notice notice-success" role="status">
          <p>쿠폰이 발급되었어요! 🎉</p>
          <Link to={ROUTES.myCoupons}>내 쿠폰 보기</Link>
        </div>
      );
    case 'failed':
      return state.reason === 'COUPON_ALREADY_ISSUED' ? (
        <p className="notice notice-info" role="status">
          {failureReasonMessage(state.reason)}
        </p>
      ) : (
        <div className="notice notice-error" role="alert">
          <p>{failureReasonMessage(state.reason)}</p>
          <button type="button" onClick={onRetry}>
            다시 시도
          </button>
        </div>
      );
    case 'rejected':
      return isFinalRejection(state.code) ? (
        <p className={`notice ${state.code === 'COUPON_ALREADY_ISSUED' ? 'notice-info' : 'notice-warn'}`} role="status">
          {errorMessageOf(state.error)}
        </p>
      ) : (
        <div className="notice notice-error" role="alert">
          <p>{errorMessageOf(state.error)}</p>
          <button type="button" onClick={onRetry}>
            다시 시도
          </button>
        </div>
      );
    case 'timeout':
      return (
        <div className="notice notice-warn" role="status">
          <p>처리가 늦어지고 있어요. 잠시 후 내 쿠폰에서 발급 여부를 확인해 주세요.</p>
          <Link to={ROUTES.myCoupons}>내 쿠폰 보기</Link>
        </div>
      );
    case 'lost':
      return (
        <div className="notice notice-warn" role="status">
          <p>{errorMessageOf(state.error)}</p>
          <Link to={ROUTES.myCoupons}>내 쿠폰 보기</Link>
        </div>
      );
  }
}

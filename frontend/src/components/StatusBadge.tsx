import type { EventStatus } from '../api/coupons';
import { STATUS_LABELS } from '../utils/eventStatus';

export function StatusBadge({ status }: { status: EventStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{STATUS_LABELS[status]}</span>;
}

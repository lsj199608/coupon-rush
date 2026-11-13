import type { EventStatus } from '../api/coupons';

export const STATUS_LABELS: Record<EventStatus, string> = {
  UPCOMING: '오픈 전',
  OPEN: '진행 중',
  ENDED: '종료',
};

export function StatusBadge({ status }: { status: EventStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{STATUS_LABELS[status]}</span>;
}

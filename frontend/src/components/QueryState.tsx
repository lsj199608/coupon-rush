import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { errorMessageOf } from '../api/errors';
import { Spinner } from './Spinner';

interface QueryStateProps<T> {
  query: UseQueryResult<T>;
  children: (data: T) => ReactNode;
  loadingLabel?: string;
  /** 데이터가 비었을 때 보여줄 문구 (배열이 비면 이 문구) */
  emptyMessage?: string;
}

/**
 * 서버 데이터 화면의 로딩 / 에러 / 빈 상태를 한 가지 모양으로 통일한다.
 * 에러 문구는 api/errors 의 코드별 메시지를 쓰고, [다시 시도]는 같은 쿼리를 다시 부른다.
 */
export function QueryState<T>({ query, children, loadingLabel, emptyMessage }: QueryStateProps<T>) {
  if (query.isPending) {
    return <Spinner label={loadingLabel} />;
  }
  if (query.isError) {
    return (
      <div className="notice notice-error" role="alert">
        <p>{errorMessageOf(query.error)}</p>
        <button type="button" onClick={() => void query.refetch()}>
          다시 시도
        </button>
      </div>
    );
  }
  if (emptyMessage && Array.isArray(query.data) && query.data.length === 0) {
    return <p className="muted">{emptyMessage}</p>;
  }
  return <>{children(query.data)}</>;
}

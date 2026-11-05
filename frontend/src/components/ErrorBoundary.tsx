import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** 이 값이 바뀌면 에러 상태를 풀고 다시 그린다 (다른 화면으로 이동하면 복구되게 경로를 넘긴다) */
  resetKey?: unknown;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * 렌더링 중 예상하지 못한 예외(코드 버그)가 나도 앱 전체가 하얀 화면이 되지 않게 막는다.
 * - 서버 에러(API 실패)는 여기로 오지 않는다 — TanStack Query 가 error 상태로 돌려주고 QueryState 가 그린다
 * - 이벤트 핸들러·비동기 코드의 예외는 React 가 잡지 않으므로 해당 코드에서 처리한다
 * 함수형 컴포넌트에는 에러 경계 API(getDerivedStateFromError)가 없어서 클래스로 만든다.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 운영이라면 여기서 Sentry 같은 수집 도구로 보낸다
    console.error('화면 렌더링 오류', error, info.componentStack);
  }

  componentDidUpdate(prev: ErrorBoundaryProps) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  private retry = () => this.setState({ error: null });

  render() {
    if (!this.state.error) {
      return this.props.children;
    }
    // 라우터 바깥에서도 쓸 수 있게 Link 대신 a 태그 (홈으로 = 전체 새로고침으로 상태 초기화)
    return (
      <section className="state-page" role="alert">
        <h1>화면을 표시하지 못했어요</h1>
        <p className="muted">일시적인 문제일 수 있어요. 다시 시도하거나 홈으로 이동해 주세요.</p>
        <div className="actions">
          <button type="button" onClick={this.retry}>
            다시 시도
          </button>
          <a href="/" className="button-link">
            홈으로
          </a>
        </div>
      </section>
    );
  }
}

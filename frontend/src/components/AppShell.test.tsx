import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/auth';
import { renderApp } from '../test/renderApp';
import { ErrorBoundary } from './ErrorBoundary';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});

function Bomb({ explode }: { explode: boolean }) {
  if (explode) throw new Error('렌더링 버그');
  return <p>정상 화면</p>;
}

/** 다시 시도 시 정상으로 그려지는지 보려고, 바깥에서 터질지 말지를 바꿀 수 있게 한다 */
function Harness({ resetKey }: { resetKey?: string }) {
  const [explode, setExplode] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setExplode(false)}>
        버그 고치기
      </button>
      <ErrorBoundary resetKey={resetKey}>
        <Bomb explode={explode} />
      </ErrorBoundary>
    </>
  );
}

describe('에러 바운더리', () => {
  it('렌더링 중 예외가 나면 앱 전체 대신 안내 화면을 보여 주고, 다시 시도하면 복구된다', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<Harness />);

    expect(screen.getByRole('alert')).toHaveTextContent('화면을 표시하지 못했어요');
    expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
    expect(consoleError).toHaveBeenCalled();   // componentDidCatch 가 기록

    await userEvent.click(screen.getByRole('button', { name: '버그 고치기' }));
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(screen.getByText('정상 화면')).toBeInTheDocument();
  });

  it('resetKey(경로)가 바뀌면 에러 상태가 풀린다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { rerender } = render(
      <ErrorBoundary resetKey="/a">
        <Bomb explode />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    rerender(
      <ErrorBoundary resetKey="/b">
        <Bomb explode={false} />
      </ErrorBoundary>,
    );

    expect(screen.getByText('정상 화면')).toBeInTheDocument();
  });
});

describe('404 페이지', () => {
  it('없는 주소는 404 화면과 목록으로 돌아가는 링크, 헤더는 그대로', async () => {
    vi.mocked(authApi.restoreSession).mockRejectedValue(new Error('no cookie'));

    renderApp('/no/such/page');

    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이벤트 목록으로' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Coupon Rush' })).toBeInTheDocument();
  });
});

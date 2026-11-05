import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { App } from '../App';
import { AuthProvider } from '../auth/AuthContext';

/** 실제 앱과 같은 Provider 구성으로 특정 경로를 렌더링한다 (쿼리 재시도는 끈다) */
export function renderApp(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

/** 백엔드 에러 응답({"code","message"})을 흉내 낸 axios 에러 */
export function apiError(status: number, code: string): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(code, String(status), config, undefined, {
    status,
    statusText: code,
    headers: {},
    config,
    data: { code, message: code },
  });
}

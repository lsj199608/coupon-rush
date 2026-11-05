import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { tokenStore } from './tokenStore';

/** 백엔드 공통 에러 응답 {"code","message"} */
export interface ApiError {
  code: string;
  message: string;
}

export interface AccessTokenResponse {
  accessToken: string;
  expiresIn: number;
}

const REFRESH_URL = '/api/auth/refresh';

/**
 * 모든 API 호출용 axios 인스턴스.
 * - baseURL 없음: 같은 출처(5173)로 보내고 Vite 프록시가 8080 으로 넘긴다 → CORS 불필요, 쿠키도 같은 출처로 동작
 * - 요청 인터셉터: 메모리의 access token 을 Authorization: Bearer 로 붙인다
 * - 응답 인터셉터: 401 이면 refresh 쿠키로 재발급(POST /api/auth/refresh) 후 원래 요청을 한 번 다시 보낸다
 */
export const apiClient: AxiosInstance = axios.create({
  withCredentials: true,
});

/**
 * 재발급은 동시에 하나만. 화면에서 API 여러 개가 동시에 401 을 받아도 refresh 는 한 번만 호출하고 모두 그 결과를 기다린다.
 * (React StrictMode 에서 effect 가 두 번 도는 경우, 콜백 페이지와 앱 시작 복원이 겹치는 경우도 같은 Promise 를 공유)
 */
let refreshInFlight: Promise<string> | null = null;

export function refreshAccessToken(): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = apiClient
      .post<AccessTokenResponse>(REFRESH_URL)
      .then((response) => {
        tokenStore.set(response.data.accessToken);
        return response.data.accessToken;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

apiClient.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiError>) => {
    const config = error.config as RetriableConfig | undefined;
    const status = error.response?.status;

    // 재발급할 상황이 아니면 그대로 실패: 401 이 아님 / 이미 한 번 재시도함 / 인증 API 자체(refresh·logout)의 401
    if (status !== 401 || !config || config._retried || config.url?.startsWith('/api/auth/')) {
      return Promise.reject(error);
    }

    config._retried = true;
    try {
      const newToken = await refreshAccessToken();
      config.headers.Authorization = `Bearer ${newToken}`;
      return apiClient(config);
    } catch (refreshError) {
      // refresh 도 실패(쿠키 없음/만료/로그아웃됨) → 세션 종료. 원래 요청의 에러를 돌려준다
      tokenStore.clear();
      tokenStore.notifySessionExpired();
      return Promise.reject(error);
    }
  },
);

/** 테스트용: 진행 중인 재발급 상태 초기화 */
export function resetRefreshStateForTest() {
  refreshInFlight = null;
}

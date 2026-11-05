import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RETURN_TO_KEY } from './AuthContext';
import * as authApi from '../api/auth';
import * as couponsApi from '../api/coupons';
import { tokenStore } from '../api/tokenStore';
import { renderApp } from '../test/renderApp';

vi.mock('../api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return { ...actual, restoreSession: vi.fn(), fetchMe: vi.fn(), logout: vi.fn() };
});
vi.mock('../api/coupons', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/coupons')>();
  return { ...actual, fetchEvents: vi.fn(), fetchMyCoupons: vi.fn() };
});

const me: authApi.Me = { id: 1, provider: 'KAKAO', nickname: '홍길동', email: null, role: 'USER' };

function loggedIn() {
  vi.mocked(authApi.restoreSession).mockImplementation(async () => {
    tokenStore.set('access-token');
    return 'access-token';
  });
  vi.mocked(authApi.fetchMe).mockResolvedValue(me);
}

function anonymous() {
  vi.mocked(authApi.restoreSession).mockRejectedValue(new Error('no refresh cookie'));
}

describe('로그인 흐름', () => {
  beforeEach(() => {
    vi.mocked(authApi.logout).mockImplementation(async () => {
      tokenStore.clear();
    });
    vi.mocked(couponsApi.fetchEvents).mockResolvedValue([]);
    vi.mocked(couponsApi.fetchMyCoupons).mockResolvedValue([]);
  });

  it('refresh 쿠키로 세션이 복원되면 보호된 화면과 헤더에 닉네임·로그아웃 버튼이 보인다', async () => {
    loggedIn();

    renderApp('/me/coupons');

    expect(await screen.findByRole('heading', { name: '내 쿠폰' })).toBeInTheDocument();
    expect(screen.getByText('홍길동', { selector: '.nickname' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument();
  });

  it('세션 복원(refresh) 응답 전에는 보호 화면을 기다리게 하고, 로그인/로그아웃 버튼 어느 쪽도 먼저 보여 주지 않는다', async () => {
    let finish!: () => void;
    vi.mocked(authApi.restoreSession).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () => {
            tokenStore.set('access-token');
            resolve('access-token');
          };
        }),
    );
    vi.mocked(authApi.fetchMe).mockResolvedValue(me);

    renderApp('/me/coupons');

    // 아직 모른다 → 로그인 화면으로 튕기지 않고(새로고침 깜빡임 방지) 확인 중 표시
    expect(screen.getByText('로그인 상태 확인 중…')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument();

    finish();

    expect(await screen.findByRole('heading', { name: '내 쿠폰' })).toBeInTheDocument();
  });

  it('refresh 는 됐는데 /me 가 실패하면 비로그인으로 처리한다', async () => {
    vi.mocked(authApi.restoreSession).mockImplementation(async () => {
      tokenStore.set('access-token');
      return 'access-token';
    });
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('500'));

    renderApp('/me/coupons');

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
  });

  it('비로그인 상태로 보호된 화면에 오면 로그인 화면으로 보낸다', async () => {
    anonymous();

    renderApp('/me/coupons');

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument();
  });

  it('이벤트 목록은 로그인하지 않아도 볼 수 있고 헤더에 로그인 링크가 있다', async () => {
    anonymous();

    renderApp('/');

    expect(await screen.findByRole('heading', { name: '이벤트' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument();
  });

  it('로그인 화면에 카카오·네이버·구글 버튼이 있고, 누르면 백엔드 OAuth 주소로 이동한다', async () => {
    anonymous();
    const assign = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location);

    renderApp('/login');
    await screen.findByRole('heading', { name: '로그인' });

    await userEvent.click(screen.getByRole('button', { name: '카카오로 로그인' }));
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/kakao');
    await userEvent.click(screen.getByRole('button', { name: '네이버로 로그인' }));
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/naver');
    await userEvent.click(screen.getByRole('button', { name: '구글로 로그인' }));
    expect(assign).toHaveBeenCalledWith('/oauth2/authorization/google');
  });

  it('콜백 페이지는 URL 에서 토큰을 읽지 않고 refresh 로 로그인을 완료한 뒤 원래 가려던 곳으로 보낸다', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '/me/coupons');
    // 콜백에 도착했을 때는 백엔드가 이미 refresh 쿠키를 심어 둔 상태 → refresh 성공
    // (실제 앱에서는 콜백 페이지와 앱 시작 복원이 같은 refresh 요청을 공유한다 — client.ts single-flight)
    loggedIn();
    window.history.replaceState(null, '', '/oauth/callback');   // URL 에 토큰 없음

    renderApp('/oauth/callback');

    expect(await screen.findByRole('heading', { name: '내 쿠폰' })).toBeInTheDocument();
    expect(tokenStore.get()).toBe('access-token');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBeNull();   // 돌아갈 경로는 한 번 쓰고 지운다
    window.history.replaceState(null, '', '/');
  });

  it('콜백에 에러가 오면 로그인 화면에 실패 메시지를 보여 준다', async () => {
    anonymous();
    window.history.replaceState(null, '', '/oauth/callback#error=login_failed');

    renderApp('/oauth/callback');

    expect(await screen.findByRole('alert')).toHaveTextContent('로그인하지 못했습니다');
    window.history.replaceState(null, '', '/');
  });

  it('로그아웃하면 서버에 알리고 메모리 토큰을 지운 뒤 로그인 화면으로 간다', async () => {
    loggedIn();
    renderApp('/me/coupons');
    await screen.findByRole('heading', { name: '내 쿠폰' });

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }));

    expect(authApi.logout).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });

  it('API 호출 중 재발급까지 실패해 세션이 만료되면 로그인 화면으로 보낸다', async () => {
    loggedIn();
    renderApp('/me/coupons');
    await screen.findByRole('heading', { name: '내 쿠폰' });

    tokenStore.clear();
    tokenStore.notifySessionExpired();   // axios 인터셉터가 refresh 실패 시 하는 일

    await waitFor(() => expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument());
  });
});

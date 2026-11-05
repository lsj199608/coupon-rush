import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { tokenStore } from './tokenStore';

describe('apiClient 인터셉터', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('메모리의 access token 을 Authorization 헤더로 붙인다', async () => {
    tokenStore.set('token-1');
    mock.onGet('/api/users/me').reply((config) => [200, { auth: config.headers?.Authorization }]);

    const response = await apiClient.get('/api/users/me');

    expect(response.data.auth).toBe('Bearer token-1');
  });

  it('토큰이 없으면 헤더를 붙이지 않는다', async () => {
    mock.onGet('/api/coupons/1').reply((config) => [200, { auth: config.headers?.Authorization ?? null }]);

    const response = await apiClient.get('/api/coupons/1');

    expect(response.data.auth).toBeNull();
  });

  it('401 이면 refresh 로 재발급받고 새 토큰으로 원래 요청을 다시 보낸다', async () => {
    tokenStore.set('expired');
    mock.onPost('/api/auth/refresh').reply(200, { accessToken: 'fresh', expiresIn: 1800 });
    mock.onGet('/api/users/me').reply((config) =>
      config.headers?.Authorization === 'Bearer fresh'
        ? [200, { nickname: '홍길동' }]
        : [401, { code: 'TOKEN_EXPIRED', message: '만료' }],
    );

    const response = await apiClient.get('/api/users/me');

    expect(response.data.nickname).toBe('홍길동');
    expect(tokenStore.get()).toBe('fresh');
  });

  it('동시에 여러 요청이 401 을 받아도 refresh 는 한 번만 호출한다', async () => {
    tokenStore.set('expired');
    let refreshCalls = 0;
    mock.onPost('/api/auth/refresh').reply(() => {
      refreshCalls += 1;
      return [200, { accessToken: 'fresh', expiresIn: 1800 }];
    });
    mock.onGet(/\/api\/.*/).reply((config) =>
      config.headers?.Authorization === 'Bearer fresh' ? [200, { ok: true }] : [401, { code: 'TOKEN_EXPIRED' }],
    );

    const results = await Promise.all([
      apiClient.get('/api/users/me'),
      apiClient.get('/api/users/me/coupons'),
      apiClient.get('/api/issue-requests/abc'),
    ]);

    expect(results.every((r) => r.data.ok)).toBe(true);
    expect(refreshCalls).toBe(1);
  });

  it('refresh 도 실패하면 토큰을 지우고 세션 만료를 알리며 원래 에러로 실패한다', async () => {
    tokenStore.set('expired');
    const onExpired = vi.fn();
    const unsubscribe = tokenStore.onSessionExpired(onExpired);
    mock.onPost('/api/auth/refresh').reply(401, { code: 'REFRESH_TOKEN_MISMATCH' });
    mock.onGet('/api/users/me').reply(401, { code: 'TOKEN_EXPIRED' });

    await expect(apiClient.get('/api/users/me')).rejects.toMatchObject({
      response: { status: 401, data: { code: 'TOKEN_EXPIRED' } },
    });
    expect(tokenStore.get()).toBeNull();
    expect(onExpired).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it('재시도한 요청이 또 401 이면 무한 반복하지 않는다', async () => {
    mock.onPost('/api/auth/refresh').reply(200, { accessToken: 'fresh', expiresIn: 1800 });
    mock.onGet('/api/users/me').reply(401, { code: 'TOKEN_REVOKED' });

    await expect(apiClient.get('/api/users/me')).rejects.toMatchObject({ response: { status: 401 } });
    expect(mock.history.post.filter((r) => r.url === '/api/auth/refresh')).toHaveLength(1);
  });

  it('인증 API(/api/auth/*) 자체의 401 에는 재발급을 시도하지 않는다', async () => {
    mock.onPost('/api/auth/refresh').reply(401, { code: 'REFRESH_TOKEN_MISSING' });

    await expect(apiClient.post('/api/auth/refresh')).rejects.toMatchObject({ response: { status: 401 } });
    expect(mock.history.post).toHaveLength(1);
  });

  it('401 이 아닌 에러는 그대로 돌려준다', async () => {
    mock.onPost('/api/coupons/1/issue').reply(409, { code: 'COUPON_SOLD_OUT', message: '소진' });

    await expect(apiClient.post('/api/coupons/1/issue')).rejects.toMatchObject({
      response: { status: 409, data: { code: 'COUPON_SOLD_OUT' } },
    });
    expect(mock.history.post).toHaveLength(1);
  });
});

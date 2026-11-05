import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { RETURN_TO_KEY, useAuth } from '../auth/AuthContext';
import { ROUTES } from '../routes';

/**
 * 백엔드가 소셜 로그인을 마치고 돌려보내는 곳 (/oauth/callback).
 * - 성공: URL 에는 아무것도 없다. refresh 쿠키가 이미 심어져 있으므로 completeLogin 이 /api/auth/refresh 로 access token 을 받는다
 * - 실패: 백엔드가 #error=login_failed 를 붙여 보낸다 (에러 코드만, 토큰 아님)
 */
export function OAuthCallbackPage() {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;   // StrictMode 의 effect 이중 실행 방지
    started.current = true;

    const returnTo = sessionStorage.getItem(RETURN_TO_KEY) ?? '/';
    sessionStorage.removeItem(RETURN_TO_KEY);

    if (window.location.hash.includes('error=')) {
      navigate(`${ROUTES.login}?error=login_failed`, { replace: true });
      return;
    }
    completeLogin()
      .then(() => navigate(returnTo, { replace: true }))
      .catch(() => navigate(`${ROUTES.login}?error=login_failed`, { replace: true }));
  }, [completeLogin, navigate]);

  return <p className="muted">로그인 처리 중…</p>;
}

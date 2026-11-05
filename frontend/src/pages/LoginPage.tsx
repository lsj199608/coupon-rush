import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import type { OAuthProvider } from '../api/auth';
import { useAuth } from '../auth/AuthContext';

const PROVIDERS: { id: OAuthProvider; label: string }[] = [
  { id: 'kakao', label: '카카오로 로그인' },
  { id: 'naver', label: '네이버로 로그인' },
  { id: 'google', label: '구글로 로그인' },
];

export function LoginPage() {
  const { status, startLogin } = useAuth();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  if (status === 'authenticated') {
    return <Navigate to={from} replace />;
  }

  return (
    <section className="login">
      <h1>로그인</h1>
      <p className="muted">선착순 쿠폰을 받으려면 로그인해 주세요.</p>
      {searchParams.get('error') && (
        <p role="alert" className="error">
          로그인하지 못했습니다. 다시 시도해 주세요.
        </p>
      )}
      <div className="providers">
        {PROVIDERS.map((provider) => (
          <button
            key={provider.id}
            type="button"
            className={`provider provider-${provider.id}`}
            onClick={() => startLogin(provider.id, from)}
          >
            {provider.label}
          </button>
        ))}
      </div>
    </section>
  );
}

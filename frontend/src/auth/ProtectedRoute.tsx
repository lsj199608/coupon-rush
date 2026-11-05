import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { ROUTES } from '../routes';

/** 로그인해야 볼 수 있는 화면. 세션 복원 중에는 기다렸다가, 비로그인이면 로그인 화면으로 (돌아올 주소를 들고) 보낸다 */
export function ProtectedRoute() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <p className="muted">로그인 상태 확인 중…</p>;
  }
  if (status === 'anonymous') {
    return <Navigate to={ROUTES.login} replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}

import { Outlet } from 'react-router-dom';
import { useAuth } from './AuthContext';

/**
 * 관리자 화면. ProtectedRoute 안쪽에 둔다 (비로그인 → 로그인 화면은 ProtectedRoute 가 처리).
 * 화면을 숨기는 건 편의일 뿐이고, 실제 권한 검사는 백엔드(/api/admin/** = ROLE_ADMIN)가 한다.
 */
export function AdminRoute() {
  const { user } = useAuth();

  if (user?.role !== 'ADMIN') {
    return (
      <section className="state-page">
        <h1>권한이 없어요</h1>
        <p className="muted">관리자만 볼 수 있는 화면이에요.</p>
      </section>
    );
  }
  return <Outlet />;
}

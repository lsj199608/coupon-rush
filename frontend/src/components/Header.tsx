import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ROUTES } from '../routes';

export function Header() {
  const { status, user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      navigate(ROUTES.login, { replace: true });
    }
  };

  return (
    <header className="header">
      <nav className="nav">
        <Link to={ROUTES.home} className="brand">
          Coupon Rush
        </Link>
        <NavLink to={ROUTES.home} end>
          이벤트
        </NavLink>
        {status === 'authenticated' && <NavLink to={ROUTES.myCoupons}>내 쿠폰</NavLink>}
        {status === 'authenticated' && user?.role === 'ADMIN' && <NavLink to={ROUTES.adminEventNew}>이벤트 만들기</NavLink>}
      </nav>
      {status === 'authenticated' && user && (
        <div className="user">
          <span className="nickname">{user.nickname}</span>
          <button type="button" onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      )}
      {status === 'anonymous' && (
        <Link to={ROUTES.login} className="login-link">
          로그인
        </Link>
      )}
    </header>
  );
}

import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ROUTES } from '../routes';

/** @param chatbotOpen / onToggleChatbot 챗봇 창 (로그인한 사람에게만 버튼이 보인다) */
export function Header({ chatbotOpen = false, onToggleChatbot }: { chatbotOpen?: boolean; onToggleChatbot?: () => void }) {
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
          {onToggleChatbot && (
            <button type="button" onClick={onToggleChatbot} aria-expanded={chatbotOpen} className={chatbotOpen ? 'primary' : undefined}>
              AI 챗봇
            </button>
          )}
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

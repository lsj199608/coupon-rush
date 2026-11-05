import { Route, Routes, useLocation } from 'react-router-dom';
import { AdminRoute } from './auth/AdminRoute';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { ErrorBoundary } from './components/ErrorBoundary';
import { Header } from './components/Header';
import { AdminEventCreatePage } from './pages/AdminEventCreatePage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventListPage } from './pages/EventListPage';
import { LoginPage } from './pages/LoginPage';
import { MyCouponsPage } from './pages/MyCouponsPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OAuthCallbackPage } from './pages/OAuthCallbackPage';
import { ROUTES } from './routes';

export function App() {
  const location = useLocation();

  return (
    <>
      <Header />
      <main className="main">
        {/* 화면 하나가 렌더링 중에 터져도 헤더(메뉴)는 살아 있고, 다른 화면으로 이동하면 복구된다 */}
        <ErrorBoundary resetKey={location.pathname}>
          <Routes>
            {/* 이벤트는 로그인 전에도 볼 수 있다. 발급 버튼을 누르면 로그인으로 보낸다 */}
            <Route path={ROUTES.home} element={<EventListPage />} />
            <Route path={ROUTES.eventDetail} element={<EventDetailPage />} />
            <Route path={ROUTES.login} element={<LoginPage />} />
            <Route path={ROUTES.oauthCallback} element={<OAuthCallbackPage />} />
            <Route element={<ProtectedRoute />}>
              <Route path={ROUTES.myCoupons} element={<MyCouponsPage />} />
              <Route element={<AdminRoute />}>
                <Route path={ROUTES.adminEventNew} element={<AdminEventCreatePage />} />
              </Route>
            </Route>
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </ErrorBoundary>
      </main>
    </>
  );
}

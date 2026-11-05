import { Link } from 'react-router-dom';
import { ROUTES } from '../routes';

export function NotFoundPage({ message = '주소가 잘못되었거나 없어진 페이지예요.' }: { message?: string }) {
  return (
    <section className="state-page">
      <p className="state-code">404</p>
      <h1>페이지를 찾을 수 없어요</h1>
      <p className="muted">{message}</p>
      <Link to={ROUTES.home} className="button-link">
        이벤트 목록으로
      </Link>
    </section>
  );
}

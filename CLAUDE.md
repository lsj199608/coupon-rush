# Coupon Rush - 선착순 쿠폰/티켓팅 서비스

## 목표
대량 동시 요청에서도 정확히 선착순 발급이 되는 쿠폰/티켓팅 서비스.
Redis, 메시지큐, 소셜 로그인, LLM 기능을 단계적으로 적용하는 학습 겸 포트폴리오 프로젝트.

## 기술 스택
- Backend: Spring Boot 3.x, Java 21, Gradle, JPA
- DB: PostgreSQL (pgvector 포함)
- Cache/Lock: Redis
- Message Queue: Kafka
- Auth: Spring Security OAuth2 Client (카카오/네이버/구글) + JWT
- Frontend: React + Vite + TypeScript, TanStack Query
- Infra: Docker Compose (로컬), 추후 AWS EC2 배포
- LLM: Spring AI (나중 단계)

## 구조
- backend/ : Spring Boot 프로젝트
- frontend/ : React 프로젝트
- infra/ : docker-compose.yml 등 인프라 설정

## 규칙
- 한 번에 한 기능만 구현하고, 구현 후 테스트 코드를 함께 작성한다.
- 쿠폰 발급 핵심 경로에는 LLM 호출을 절대 넣지 않는다.
- 비밀키, Client Secret은 코드에 넣지 않고 환경변수(.env)로 관리한다. .env는 .gitignore에 포함한다.
- 코드 변경 시 왜 그렇게 설계했는지 간단히 설명해준다.
- 소셜 로그인은 OAuth2UserInfo 추상화로 제공자 차이를 흡수한다.

## 개발 순서
1. 프로젝트 뼈대 + docker-compose (Postgres, Redis, Kafka)
2. 소셜 로그인 + JWT
3. 쿠폰 발급 API (DB 기반)
4. Redis로 재고 차감 개선 + 동시성 테스트
5. Kafka 비동기 처리
6. React 화면 연동
7. LLM 기능 (이벤트 문구 생성 → 자연어 검색 → 챗봇)

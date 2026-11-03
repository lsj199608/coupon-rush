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

## 진행 상황
작업 단계가 끝날 때마다 이 섹션을 갱신하고 함께 커밋한다. (여러 PC에서 이어서 작업하기 위함)

- [x] 1. 프로젝트 뼈대 + docker-compose
- [ ] 2. 소셜 로그인 + JWT  ← 진행 중
  - [x] 카카오 로그인 + access token 발급 (프론트로 #accessToken= 리다이렉트)
  - [x] 네이버, 구글 로그인 (Naver/GoogleOAuth2UserInfo)
  - [ ] JWT 인증 필터 (Authorization: Bearer) + /api/users/me  ← 다음 작업
- [ ] 3. 쿠폰 발급 API (DB 기반)
- [ ] 4. Redis로 재고 차감 개선 + 동시성 테스트
- [ ] 5. Kafka 비동기 처리
- [ ] 6. React 화면 연동
- [ ] 7. LLM 기능

## 결정 사항 / 메모
- Spring Boot 3.5.16 고정: Spring Initializr가 4.x만 제공해서 Wrapper만 받고 build.gradle에서 수동 지정.
  Gradle Wrapper는 Boot 3.5 호환을 위해 8.14.3.
- 패키지: com.couponrush (공통 설정은 com.couponrush.global.config)
- 이미지: pgvector/pgvector:pg17, redis:7.4-alpine, apache/kafka:3.9.1 (KRaft 단일 노드)
- 접속 정보는 infra/.env 하나로 관리. docker-compose와 Spring(application.yml의
  spring.config.import: optional:file:../infra/.env[.properties])이 같은 파일을 읽는다.
- Security: 현재 /actuator/health 만 공개, 나머지 인증 필요, stateless (JWT 전제).
- 테스트는 Testcontainers로 compose와 동일한 이미지를 띄워 검증한다 (Docker 실행 필요).
  통합 테스트는 @IntegrationTest 메타 애너테이션 사용 (더미 비밀값 고정 + 컨텍스트 캐시 공유).
- 회원 식별 키는 (provider, providerId). 닉네임은 바뀔 수 있어 식별에 쓰지 않고 로그인마다 갱신.
  이메일은 nullable. 테이블명 users ("user"는 PostgreSQL 예약어).
  같은 사람이 다른 제공자로 로그인하면 별도 회원 (이메일로 계정 자동 병합 안 함 — 계정 탈취 위험).
- 제공자 공통 규칙(기본 닉네임 "{registrationId}_{id}", 50자 자르기, 빈 이메일 무시)은 AbstractOAuth2UserInfo에.
  구현체는 응답에서 값을 꺼내는 위치만 정의. 새 제공자 = 구현체 + OAuth2Provider 값 + Factory switch 분기.
- 구글은 scope에 openid를 넣지 않는다 (넣으면 OidcUserService로 빠져 CustomOAuth2UserService를 안 탐).
  구글 이메일은 email_verified=true일 때만 저장. 네이버는 user-name-attribute=response (중첩 응답).
- JWT에는 userId(sub)와 role만 담는다. 토큰은 URL fragment(#)로 전달해 서버 로그/Referer에 남지 않게 한다.
- 미인증 API 요청은 로그인 리다이렉트(302)가 아니라 401.
- 스키마는 Flyway로만 변경한다 (backend/src/main/resources/db/migration/V{n}__설명.sql).
  ddl-auto: validate — 엔티티와 스키마가 다르면 기동 실패. 이미 적용된 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.
- enum(OAuth2Provider, Role)에 값을 추가하면 CHECK 제약(users_provider_check, users_role_check)을
  바꾸는 마이그레이션도 추가해야 한다. 빠뜨리면 FlywayMigrationTest가 실패한다.
- Flyway 도입 전(ddl-auto: update 시절)에 만들어진 로컬 DB는 "non-empty schema without schema history table"로
  기동이 실패한다. 로컬 테스트 데이터뿐이므로 DROP TABLE users; 후 재기동하면 V1부터 적용된다.

## 새 PC에서 시작하기
1. Java 21, Docker Desktop, Git 설치 (Gradle은 gradlew가 받음)
2. git clone https://github.com/lsj199608/coupon-rush.git
3. infra/.env.example → infra/.env 복사 후 값 채우기 (.env는 git에 없음)
4. git config --global user.name / user.email 설정
5. 확인: cd infra && docker compose up -d → cd ../backend && ./gradlew test

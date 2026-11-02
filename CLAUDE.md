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
- [ ] 2. 소셜 로그인 + JWT  ← 다음 작업 (구글 먼저, OAuth2UserInfo 추상화부터)
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

## 새 PC에서 시작하기
1. Java 21, Docker Desktop, Git 설치 (Gradle은 gradlew가 받음)
2. git clone https://github.com/lsj199608/coupon-rush.git
3. infra/.env.example → infra/.env 복사 후 값 채우기 (.env는 git에 없음)
4. git config --global user.name / user.email 설정
5. 확인: cd infra && docker compose up -d → cd ../backend && ./gradlew test

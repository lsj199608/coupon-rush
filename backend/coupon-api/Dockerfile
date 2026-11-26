# coupon-api 이미지 (8단계 Kubernetes). 빌드 컨텍스트는 backend/ (멀티모듈 루트)
#   docker build -f backend/coupon-api/Dockerfile -t coupon-rush/coupon-api:local backend   (저장소 루트에서)
# 모양은 네 서비스가 같다 (llm-service/Dockerfile 과 같은 구조) — 다른 것은 모듈 이름·의존 모듈 소스·포트뿐
#
# 1단계: Gradle 로 bootJar 만 만든다 (테스트는 로컬/CI 에서 — 이미지 빌드에 Docker 안의 Docker 가 필요해지므로)
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# 의존성 계층을 소스와 분리 — 빌드 설정이 그대로면 소스만 바뀌어도 의존성을 다시 받지 않는다.
# settings.gradle 이 모든 모듈을 include 하므로 모듈 build.gradle 은 전부 넣는다 (폴더가 없으면 Gradle 경고)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY coupon-api/build.gradle coupon-api/build.gradle
COPY llm-service/build.gradle llm-service/build.gradle
COPY mcp-server/build.gradle mcp-server/build.gradle
COPY web-chat-service/build.gradle web-chat-service/build.gradle
COPY libs/llm-contract/build.gradle libs/llm-contract/build.gradle
COPY libs/web-chat-contract/build.gradle libs/web-chat-contract/build.gradle
RUN chmod +x gradlew && ./gradlew :coupon-api:dependencies --no-daemon -q > /dev/null

COPY libs/llm-contract/src libs/llm-contract/src
COPY libs/web-chat-contract/src libs/web-chat-contract/src
COPY coupon-api/src coupon-api/src
RUN ./gradlew :coupon-api:bootJar --no-daemon -q

# 2단계: 실행 이미지 (JRE + 헬스체크용 curl, root 아닌 사용자)
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/coupon-api/build/libs/coupon-api-*.jar app.jar
USER app

EXPOSE 8080
# Docker/compose 용. Kubernetes 는 이 값을 쓰지 않고 매니페스트의 readiness/liveness 로 확인한다 (8-3)
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

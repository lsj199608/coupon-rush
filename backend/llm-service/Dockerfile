# llm-service 이미지 (compose 의 llm 프로필). 빌드 컨텍스트는 backend/ (멀티모듈 루트)
#   docker compose --profile llm build   (infra/ 에서)
#
# 1단계: Gradle 로 bootJar 만 만든다 (테스트는 로컬/CI 에서 — 이미지 빌드에 Docker 안의 Docker 가 필요해지므로)
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# 의존성 계층을 소스와 분리 — 빌드 설정이 그대로면 소스만 바뀌어도 의존성을 다시 받지 않는다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY coupon-api/build.gradle coupon-api/build.gradle
COPY llm-service/build.gradle llm-service/build.gradle
COPY libs/llm-contract/build.gradle libs/llm-contract/build.gradle
RUN chmod +x gradlew && ./gradlew :llm-service:dependencies --no-daemon -q > /dev/null

COPY libs/llm-contract/src libs/llm-contract/src
COPY llm-service/src llm-service/src
RUN ./gradlew :llm-service:bootJar --no-daemon -q

# 2단계: 실행 이미지 (JRE + 헬스체크용 curl, root 아닌 사용자)
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/llm-service/build/libs/llm-service-*.jar app.jar
USER app

# 8081 은 헬스체크(actuator)용 — compose 에서 호스트로 열지 않는다 (공개 API 없음)
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

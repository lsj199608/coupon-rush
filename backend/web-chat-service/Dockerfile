# web-chat-service 이미지 (8단계 Kubernetes). 빌드 컨텍스트는 backend/ (멀티모듈 루트)
#   docker build -f backend/web-chat-service/Dockerfile -t coupon-rush/web-chat-service:local backend   (저장소 루트에서)
# 구조는 coupon-api/Dockerfile 과 같다. 의존 모듈은 web-chat-contract 하나
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY coupon-api/build.gradle coupon-api/build.gradle
COPY llm-service/build.gradle llm-service/build.gradle
COPY mcp-server/build.gradle mcp-server/build.gradle
COPY web-chat-service/build.gradle web-chat-service/build.gradle
COPY libs/llm-contract/build.gradle libs/llm-contract/build.gradle
COPY libs/web-chat-contract/build.gradle libs/web-chat-contract/build.gradle
RUN chmod +x gradlew && ./gradlew :web-chat-service:dependencies --no-daemon -q > /dev/null

COPY libs/web-chat-contract/src libs/web-chat-contract/src
COPY web-chat-service/src web-chat-service/src
RUN ./gradlew :web-chat-service:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/web-chat-service/build/libs/web-chat-service-*.jar app.jar
USER app

EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

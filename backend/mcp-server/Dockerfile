# mcp-server 이미지 (8단계 Kubernetes). 빌드 컨텍스트는 backend/ (멀티모듈 루트)
#   docker build -f backend/mcp-server/Dockerfile -t coupon-rush/mcp-server:local backend   (저장소 루트에서)
# 구조는 coupon-api/Dockerfile 과 같다. 다른 모듈에 의존하지 않아 자기 소스만 넣는다
#
# ⚠ application.yml 의 server.address=127.0.0.1 (루프백만) 그대로면 다른 Pod 가 닿지 않는다 →
#   클러스터에서는 8-3 에서 0.0.0.0 + NetworkPolicy(llm-service 만 들어오게)로 바꾼다. 헬스체크는 컨테이너 안 localhost 라 지금도 동작
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
RUN chmod +x gradlew && ./gradlew :mcp-server:dependencies --no-daemon -q > /dev/null

COPY mcp-server/src mcp-server/src
RUN ./gradlew :mcp-server:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/mcp-server/build/libs/mcp-server-*.jar app.jar
USER app

EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# 프론트 이미지 (8단계 Kubernetes). 빌드 컨텍스트는 frontend/
#   docker build -t coupon-rush/frontend:local frontend   (저장소 루트에서)
# 정적 파일 + 게이트웨이(8-4: /api·/oauth2·/login/oauth2·/ws/web-chat 리버스 프록시, 상세 1초 캐시) — 설정은 nginx/*.conf.
# ⚠ 백엔드 주소가 Kubernetes Service 이름(coupon-api, web-chat-service)이라 클러스터 밖 docker run 으로는 뜨지 않는다
#   (nginx 는 기동할 때 proxy_pass 이름을 DNS 로 풀고, 못 풀면 멈춘다)
#
# 1단계: npm ci + npm run build (tsc 타입 검사 + vite build → dist/). 테스트는 로컬/CI 에서
FROM node:26-alpine AS build
WORKDIR /app
# 의존성 계층을 소스와 분리 — package*.json 이 그대로면 소스만 바뀌어도 npm ci 를 다시 하지 않는다
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# 2단계: Nginx (정적 파일 + 리버스 프록시), root 아닌 nginx 사용자(uid 101)
# nginx:alpine 을 쓰고 비root 설정은 직접 한다 — 비root 전용 이미지(nginxinc/nginx-unprivileged:alpine)는
# 2026-11-26 검사에서 Critical 9 / High 31 로 nginx:alpine(1 / 2)보다 훨씬 많았다
FROM nginx:alpine
# 비root 로 돌리기: user 지시어 제거(root 가 아니면 경고만 남김), pid·캐시 경로를 nginx 사용자가 쓸 수 있게.
# 1024 미만 포트는 root 만 열 수 있어서 8080 으로 듣는다
# apk upgrade: 기본 이미지가 다시 빌드되기 전에 나온 보안 수정판을 받는다 (2026-11-26 zlib 1.3.2 → 1.4, Critical 1)
RUN apk upgrade --no-cache \
    && sed -i '/^user /d' /etc/nginx/nginx.conf \
    && sed -i 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /var/cache/nginx \
    && rm /etc/nginx/conf.d/default.conf
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/proxy-headers.conf nginx/security-headers.conf /etc/nginx/snippets/
COPY --from=build /app/dist /usr/share/nginx/html
USER nginx

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1

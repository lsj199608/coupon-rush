#!/bin/sh
# web-chat-service 의 자기 데이터베이스 web_chat + 전용 계정 (7단계 D6, "서비스마다 자기 DB").
#
# 언제 실행되나: Postgres 컨테이너가 "빈 볼륨으로 처음" 만들어질 때 docker-entrypoint-initdb.d 에서 자동으로 1회.
#   이미 있는 볼륨(지금까지 쓰던 로컬 DB)에서는 돌지 않는다 → 직접 한 번 실행 (README "채팅 서버" 절):
#   docker exec coupon-rush-postgres sh /docker-entrypoint-initdb.d/20-web-chat-db.sh
# 여러 번 실행해도 된다 (이미 있는 계정·데이터베이스는 건너뜀 — 단, 이미 있는 계정의 비밀번호는 바꾸지 않는다).
#
# 권한: web_chat 계정은 web_chat 데이터베이스의 주인일 뿐 (슈퍼유저 아님). 다른 데이터베이스는 PUBLIC 의 CONNECT 를 거둬
#   coupon_rush 에 접속조차 못 하게 한다 (테이블 권한이 없어도 접속부터 막는다 — 실수로 다른 서비스 데이터를 읽지 않게).
set -eu

: "${WEB_CHAT_DB_PASSWORD:?WEB_CHAT_DB_PASSWORD 가 필요합니다 (infra/.env)}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v wc_db=web_chat \
  -v wc_user="${WEB_CHAT_DB_USER:-web_chat}" \
  -v wc_password="$WEB_CHAT_DB_PASSWORD" \
  -v main_db="$POSTGRES_DB" <<'EOSQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'wc_user', :'wc_password')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'wc_user')\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'wc_db', :'wc_user')
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'wc_db')\gexec
REVOKE ALL ON DATABASE :"wc_db" FROM PUBLIC;
REVOKE CONNECT ON DATABASE :"main_db" FROM PUBLIC;
EOSQL

echo "web_chat 데이터베이스·계정 준비 완료"

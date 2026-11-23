-- 회원별 도배 제한 — 미끄러지는 창 (모든 web-chat-service 서버가 같은 키를 본다)
-- KEYS[1] = web-chat:rate:{userId}  (ZSET: member = 시도마다 고유 ID, score = 보낸 시각 epoch ms)
-- ARGV[1] = 지금 (epoch ms), ARGV[2] = 창 (ms), ARGV[3] = 창 안 최대 개수, ARGV[4] = 이번 시도의 고유 ID
-- 반환: 1 = 보낼 수 있음(1회 셈), 0 = 한도 초과(세지 않음)
-- 창은 (지금 - 창, 지금] — 정확히 창 길이만큼 지난 기록은 빠진다
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
    return 0
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
-- 마지막 기록이 창을 벗어나면 키가 통째로 사라진다 (말 없는 회원의 정리 작업이 필요 없음)
redis.call('PEXPIRE', KEYS[1], window)
return 1

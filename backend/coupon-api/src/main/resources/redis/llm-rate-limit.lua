-- LLM 요청 사용자별 한도 (고정 창). 확인과 증가를 한 번에 — GET 후 INCR 을 따로 하면 동시 요청이 함께 통과한다.
-- 한도를 넘은 요청은 카운터를 올리지 않는다 (거절된 요청이 다음 창의 기회를 깎지 않게).
-- KEYS[1] = llm:rate:{userId}:d:{yyyyMMdd}       (일)
-- KEYS[2] = llm:rate:{userId}:m:{yyyyMMddHHmm}   (분)
-- ARGV[1] = 일 한도, ARGV[2] = 분 한도, ARGV[3] = 일 키 TTL(초), ARGV[4] = 분 키 TTL(초)
-- 반환: 0 = 허용, 1 = 분 한도 초과, 2 = 일 한도 초과

local day = tonumber(redis.call('GET', KEYS[1]) or '0')
if day >= tonumber(ARGV[1]) then
    return 2
end
local minute = tonumber(redis.call('GET', KEYS[2]) or '0')
if minute >= tonumber(ARGV[2]) then
    return 1
end

if redis.call('INCR', KEYS[1]) == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[3])
end
if redis.call('INCR', KEYS[2]) == 1 then
    redis.call('EXPIRE', KEYS[2], ARGV[4])
end
return 0

-- 보상: Redis 에서는 SUCCESS 였지만 DB 저장이 실패한 경우 되돌린다.
-- KEYS[1] = event:{id}:stock, KEYS[2] = event:{id}:issued
-- ARGV[1] = userId, ARGV[2] = '1' 이면 Set 에서 userId 도 제거 (DB 에 이미 발급 이력이 있는 경우는 '0')

-- 키가 없으면(만료/재적재) 새로 만들지 않는다 → TTL 없는 키가 생기는 것을 막는다
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
end
if ARGV[2] == '1' then
    redis.call('SREM', KEYS[2], ARGV[1])
end
return 1

-- 보상: Redis 에서는 SUCCESS 였지만 DB 저장(또는 Kafka 발행)이 실패한 경우 되돌린다. 멱등하다.
-- KEYS[1] = event:{id}:stock, KEYS[2] = event:{id}:issued, KEYS[3] = event:{id}:restored (복구한 requestId Set)
-- ARGV[1] = userId
-- ARGV[2] = '1' 이면 발급 Set 에서 userId 도 제거 (DB 에 이미 발급 이력이 있는 경우는 '0')
-- ARGV[3] = restoreId (비동기: requestId — 발행 실패 / 컨슈머 실패 / DLT 어느 경로로 와도 같은 요청은 한 번만 복구)
-- 반환: 1 = 복구함, 0 = 이미 복구된 요청이라 아무것도 안 함

if redis.call('SADD', KEYS[3], ARGV[3]) == 0 then
    return 0
end

-- 복구 기록도 재고 키와 같이 만료되게 (재고 키가 없으면 7일)
if redis.call('PTTL', KEYS[3]) == -1 then
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl > 0 then
        redis.call('PEXPIRE', KEYS[3], ttl)
    else
        redis.call('EXPIRE', KEYS[3], 604800)
    end
end

-- 재고 키가 없으면(만료/재적재) 새로 만들지 않는다 → TTL 없는 키가 생기는 것을 막는다
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
end
if ARGV[2] == '1' then
    redis.call('SREM', KEYS[2], ARGV[1])
end
return 1

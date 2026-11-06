-- 선착순 쿠폰 발급 판정 (원자적으로 실행된다: 실행 중에는 다른 명령이 끼어들 수 없다)
-- KEYS[1] = event:{id}:stock   남은 재고 (string, 정수)
-- KEYS[2] = event:{id}:issued  발급받은 userId (set)
-- KEYS[3] = event:{id}:meta    발급 기간 (hash: start, end — epoch millis)
-- ARGV[1] = userId
-- ARGV[2] = 현재 시각 (epoch millis)
-- 반환: SUCCESS | DUPLICATE | SOLD_OUT | NOT_IN_PERIOD | NOT_READY

local stock = redis.call('GET', KEYS[1])
local startAt = redis.call('HGET', KEYS[3], 'start')
local endAt = redis.call('HGET', KEYS[3], 'end')
if not stock or not startAt or not endAt then
    return 'NOT_READY'                       -- 재고 미적재 (없는 쿠폰이거나 Redis 데이터 유실)
end

local now = tonumber(ARGV[2])
if now < tonumber(startAt) or now >= tonumber(endAt) then
    return 'NOT_IN_PERIOD'
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 'DUPLICATE'
end

if tonumber(stock) <= 0 then
    return 'SOLD_OUT'
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 첫 SADD 로 Set 이 새로 만들어졌으면 재고 키와 같은 만료 시각을 준다 (이벤트가 끝난 키가 영원히 남지 않게)
if redis.call('PTTL', KEYS[2]) == -1 then
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl > 0 then
        redis.call('PEXPIRE', KEYS[2], ttl)
    end
end

return 'SUCCESS'

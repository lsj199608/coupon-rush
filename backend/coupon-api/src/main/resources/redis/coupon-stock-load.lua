-- 쿠폰 재고 적재 (이벤트 오픈 전 / DB 기준 재동기화). 기존 키를 지우고 한 번에 다시 만든다.
-- KEYS[1] = event:{id}:stock, KEYS[2] = event:{id}:issued, KEYS[3] = event:{id}:meta
-- ARGV[1] = 남은 재고, ARGV[2] = 발급 시작(epoch millis), ARGV[3] = 발급 종료(epoch millis)
-- ARGV[4] = 키 만료 시각(epoch seconds), ARGV[5..] = 이미 발급받은 userId 들 (재동기화 시)

redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
redis.call('SET', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[3], 'start', ARGV[2], 'end', ARGV[3])
for i = 5, #ARGV do
    redis.call('SADD', KEYS[2], ARGV[i])
end

redis.call('EXPIREAT', KEYS[1], ARGV[4])
redis.call('EXPIREAT', KEYS[3], ARGV[4])
if #ARGV >= 5 then
    redis.call('EXPIREAT', KEYS[2], ARGV[4])
end
return #ARGV - 4

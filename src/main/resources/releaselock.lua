local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]

--判断锁是否是自己
if (redis.call('HEXISTS', key, threadId) == 0) then
    return nil;
end;
--如果是自己的锁，则重入次数-1
local count = redis.call('HINCRBY' , key , threadId , -1);
--判断重入次数是否为0
if(count > 0) then
    --不为0，不能释放锁，重置过期时间
    redis.call('EXPIRE' , key , releaseTime);
    return nil;
else --等于0说明可以释放锁，直接删除
    redis.call('DEL', key);
    return nil;
end;
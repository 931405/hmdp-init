local key = KEYS[1] --锁的key
local threadId = ARGV[1] --线程唯一标识
local releaseTime = ARGV[2] --锁的自动释放时间

--首先判断锁是否存在
if(redis.call('exists', key) == 0) then
    --不存在，获取锁
    redis.call('hset', key , threadId , '1');
    --设置有效期
    redis.call('expire' , key , releaseTime);

    return 1; -- 返回结果
end;
--如果锁以及存在
if(redis.call('hexists' , key , threadId) == 1) then
    --不存在，获取锁，重入次数加1
    redis.call('hincrby' , 'key' , threadId , '1');
    -- 设置有效期
    redis.call('expire' ， key , releaseTime);
    return 1;
end;
return 0;--如果锁不是自己的
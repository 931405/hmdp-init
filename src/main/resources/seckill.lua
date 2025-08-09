--参数列表
--首先是优惠券id
local voucherId = ARGV[1]
--然后是用户Id
local userId = ARGV[2]
--订单ID
local orderId = ARGV[3]
local key = 'seckill:stock:' .. voucherId
local key1 = 'seckill:order:' .. voucherId
--判断库存是否充足
if(tonumber(redis.call('get', key)) <= 0) then
    return 1
end
--判断用户是否已经领取过
if(redis.call('sismember', key1, userId) == 1) then
    return 2
end
--扣减库存
redis.call('incrby',key, -1)
--userId存入优惠券的set集合
redis.call('sadd', key1, userId)
--发送消息到队列当中 XADD stream.orders * k1 v1
redis.call('xadd', 'stream.orders', '*', 'id', orderId, 'voucherId', voucherId, 'userId', userId)
return 0
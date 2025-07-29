package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWork redisIdWork;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService. getById(voucherId);
        // 2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //尝试获取锁
        String lock = "lock:order:" + userId;
        //SimpleRedisLock lock1 = new SimpleRedisLock(lock, stringRedisTemplate);
        //这个地方选择使用redisson的方法来创建锁
        RLock lock1 = redissonClient.getLock(lock);
        boolean islock = lock1.tryLock();
        //如果获取锁失败
        if(!islock){
            return Result.fail("不能重复下单");
        }
        //获取锁成功
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getResult(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock1.unlock();
        }

    }

    @Transactional
    public Result getResult(Long voucherId) {
        UserDTO userId = UserHolder.getUser();
        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId.getId()).count();
        if(count > 0 ){
            return Result.fail("不能重复下单");
        }
        // 5.扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherId)//where voucher_id = ? and stock > 0
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }
        // 6.创建订单
        VoucherOrder order = new VoucherOrder();
        // 6.1. 生成订单号
        long orderId = redisIdWork.nextId("order");
        order.setId(orderId);
        // 6.2. 获取用户号
        order.setUserId(userId.getId());
        // 6.3. 获取优惠券号
        order.setVoucherId(voucherId);
        save( order);
        // 7.返回订单ID
        return Result.ok(orderId);
    }
}

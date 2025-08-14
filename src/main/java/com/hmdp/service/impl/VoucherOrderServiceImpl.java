package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

    private IVoucherOrderService proxy;
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String name = "stream.orders";

        @Override
        public void run() {
            while ( !Thread.currentThread().isInterrupted()){
                try {
                    //1. 获取消息队列信息 XREADGROUP GROUP g1 c1 count 1 block 2s streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(name, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        //2.1. 不成功，阻塞，继续下一次循环
                        Thread.sleep(100);
                        continue;
                    }
                    //3. 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //4. 成功，可以下单
                    //创建订单
                    HandleVoucherOrder(order);
                    //5. 返回ACK确认信息
                    stringRedisTemplate.opsForStream().acknowledge(name,"g1",record.getId());
                } catch (Exception e) {
                    log.info("未知的异常信息" + e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while ( true){
                try {
                    //1. 获取pending-list队列信息 XREADGROUP GROUP g1 c1 count 1 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(name, ReadOffset.from("0"))
                    );
                    //2. 判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        //2.1. 不成功,说明pending-list没有消息了，跳出循环
                        break;
                    }
                    //3. 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //4. 成功，可以下单
                    //创建订单
                    HandleVoucherOrder(order);
                    //5. 返回ACK确认信息
                    stringRedisTemplate.opsForStream().acknowledge(name,"g1",record.getId());
                } catch (Exception e) {
                    log.info("未知的异常信息" + e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

/*    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while ( true){
                //获取队列信息
                try {
                    VoucherOrder order = orderTasks.take();
                    //创建订单
                    HandleVoucherOrder(order);
                } catch (Exception e) {
                    log.info("未知的异常信息" + e);
                }
            }
        }
    }*/

    private void HandleVoucherOrder(VoucherOrder order) {
        //尝试获取锁
        String lock = "lock:order:" + order.getUserId();
        //这个地方选择使用redisson的方法来创建锁
        RLock lock1 = redissonClient.getLock(lock);
        boolean islock = lock1.tryLock();
        //如果获取锁失败
        if(!islock){
            log.info("不能重复下单");
            return ;
        }
        //获取锁成功
        try {
            proxy.getResult(order);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock1.unlock();
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public Result seckillVoucher(Long voucherId) {
        // 获取当前用户的ID
        Long userID = UserHolder.getUser().getId();
        //获取订单Id
        long orderId = redisIdWork.nextId("order");
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userID.toString(),String.valueOf(orderId)
        );
        // 2. 判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4. 返回结果
        return Result.ok(orderId);
    }
    @Override
/*    public Result seckillVoucher(Long voucherId) {
        // 获取当前用户的ID
        Long userID = UserHolder.getUser().getId();
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userID.toString()
        );
        // 2. 判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3. 将信息存入阻塞队列当中
        //使用消息队列
        VoucherOrder order = new VoucherOrder();
        // 3.1. 生成订单号
        long orderId = redisIdWork.nextId("order");
        order.setId(orderId);
        // 3.2. 获取用户号
        order.setUserId(userID);
        // 3.3. 获取优惠券号
        order.setVoucherId(voucherId);
        // 3.4 放入阻塞队列
        orderTasks.add(order);
        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4. 返回结果
        return Result.ok(orderId);
    }*/
/*    public Result seckillVoucher(Long voucherId) {
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

    }*/
    @Transactional
    public void getResult(VoucherOrder voucherOrder) {
        Long count = query().eq("voucher_id", voucherOrder.getVoucherId()).eq("user_id", voucherOrder.getUserId()).count();
        if(count > 0 ){
            log.info("不能重复下单");
            return ;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())//where voucher_id = ? and stock > 0
                .gt("stock", 0)
                .update();
        if(!success){
            log.info("库存不足");
            return ;
        }
        save( voucherOrder);
        // 7.返回订单ID
        log.info("下单成功");
        return;
    }
}

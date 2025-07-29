package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.baomidou.mybatisplus.extension.toolkit.Db.getById;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheClinet {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public CacheClinet(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    //定义一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //缓存数据，非逻辑过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    //缓存数据，逻辑过期时间
    public  void  setWithLogicExpire(String key, Object value, Long time){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(value));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds( time));

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData) );
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public <R, ID> R queryWithChuantou(String keyPrefix , ID id, Class<R> type, Function<ID, R> dbFallBack, Long time , TimeUnit unit){
        String key = keyPrefix + id;
        //首先根据id来查询redis
        String shop = stringRedisTemplate.opsForValue().get(key);
        //如果存在，直接返回
        if (StrUtil.isNotBlank(shop)) {
            return JSONUtil.toBean(shop,type);
        }
        //需要多判断一步，redis中读取出来的是不是null
        if (shop!= null) {
            return null;
        }

        //不存在，根据id查询数据库,这里由调用者传入数据库查询方法
        R shopData = dbFallBack.apply(id);
        log.info("此处执行了sql");
        //如果数据库也不存在
        if (shopData==null) {
            //防止缓存穿透使用redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis
        //stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopData),time, unit);
        this.set(key,shopData,time,unit);

        //返回结果

        return shopData;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,String lockPrefix,Function<ID,R> dbFallBack,Long time){
        String key = keyPrefix + id;
        //首先根据id来查询redis
        String shop = stringRedisTemplate.opsForValue().get(key);
        //如果存在，直接返回
        if (StrUtil.isBlank(shop)) {
            return null;
        }
        //判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shop, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R bean = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return bean;
        }
        //过期、尝试获取锁
        String lockkey = lockPrefix + id;
        boolean islock = tryLock(lockkey);
        //判断是否获取到锁
        if(islock){
            //获取到，开启线程来更新缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    setWithLogicExpire(key,dbFallBack.apply(id), time);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockkey);
                }
            });
        }
        //返回商铺信息
        return bean;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}

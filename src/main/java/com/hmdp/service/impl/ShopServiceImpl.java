package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClinet;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collector;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClinet cacheClinet;


    //定义一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用redis查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //huancunchuantou( id);
        Shop shop = cacheClinet.queryWithChuantou(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //使用互斥锁解决缓存击穿问题
/*        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);*/
        //使用逻辑过期时间解决缓存击穿问题
        //Shop shop = cacheClinet.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, this::getById, LOCK_SHOP_TTL);
        return Result.ok(shop);
    }

    /**
     * 使用redis更新商铺信息
     * @param shop
     */
    @Override
    @Transactional  //添加事务
    public Result updateByShopId(Shop shop) {
        Long id = shop.getId();
        if (id==null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById( shop);
        //更新缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //首先对x,y进行判断，是否未空
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //如果不为空，计算分页参数
        Integer from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        Integer to = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        //查询redis，按照距离进行排序，分页，结果：shopId，distance
        //GEOSEARCH KEY [BYMEMBER] BYLONGLAT x y RADIUS distance WITHDISTANCE WITHCOORDINATES ASC LIMIT from to
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(to)
        );
        if (results == null ) {
            return Result.ok(Collections.emptyList());
        }
        //解析数据
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        //创建一个列表来接收shopId
        List<Long> ids = new ArrayList<>(content.size());
        //创建一个map来实现shopId和distance一一对应
        Map<String,Double> distanceMap = new HashMap<>(content.size());
        if(from >= content.size()){
            return Result.ok(Collections.emptyList());
        }
        //截取from到to的元素
        content.stream().skip( from).forEach(result ->{
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance.getValue());
        });
        //根据shopId查询shop信息
        String s = StrUtil.join(",", ids);
        List<Shop> list = query().in("id", ids).last(" order by field ( id," + s + ")").list();
        list.forEach(shop ->{
            shop.setDistance(distanceMap.get(shop.getId().toString()));
        });
        //返回
        return Result.ok(list);

    }

/*
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //首先根据id来查询redis
        String shop = stringRedisTemplate.opsForValue().get(key);
        //如果存在，直接返回
        if (StrUtil.isBlank(shop)) {
            return null;
        }
        //判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shop, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop bean = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return bean;
        }
        //过期、尝试获取锁
        String lockkey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean islock = tryLock(lockkey);
        //判断是否获取到锁
        if(islock){
            //获取到，开启线程来更新缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    save2RedisShop(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock.lua(lockkey);
                }
            });
        }
        //返回商铺信息
        return bean;
    }*/

    /*public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //缓存穿透
        //huancunchuantou( id);
        //首先根据id来查询redis
        String shop = stringRedisTemplate.opsForValue().get(key);
        //如果存在，直接返回
        if (StrUtil.isNotBlank(shop)) {
            return JSONUtil.toBean(shop,Shop.class);
        }
        //需要多判断一步，redis中读取出来的是不是null
        if (shop != null) {
            return null;
        }
        String lockKey = null;
        Shop shopData = null;
        try {
            //如果不存在尝试获取锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean tryLock = tryLock(lockKey);
            //获取失败，休眠并等一会尝试
            if (!tryLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取成功，查询数据写入redis
            //不存在，根据id查询数据库
            shopData = getById(id);
            //如果数据库也不存在
            if (shopData==null) {
                //防止缓存穿透使用redis
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopData),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock.lua(lockKey);
        }
        //返回结果

        return shopData;
    }*/

/*

    public Shop huancunchuantou(Long id){
        String key = CACHE_SHOP_KEY + id;
        //首先根据id来查询redis
        String shop = stringRedisTemplate.opsForValue().get(key);
        //如果存在，直接返回
        if (StrUtil.isNotBlank(shop)) {
            return JSONUtil.toBean(shop,Shop.class);
        }
        //需要多判断一步，redis中读取出来的是不是null
        if (shop != null) {
            return null;
        }

        //不存在，根据id查询数据库
        Shop shopData = getById(id);
        //如果数据库也不存在
        if (shopData==null) {
            //防止缓存穿透使用redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopData),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回结果

        return shopData;
    }*/
/*    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock.lua(String key){
        stringRedisTemplate.delete(key);
    }

    public void save2RedisShop(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(shop));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
}

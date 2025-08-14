import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClinet;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWork;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@SpringBootTest(classes = com.hmdp.HmDianPingApplication.class)  // 指定主应用类
public class testRedisShop {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClinet cacheClinet;
    @Resource
    private RedisIdWork redisIdWork;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void test() throws InterruptedException {
        cacheClinet.setWithLogicExpire(RedisConstants.CACHE_SHOP_KEY + 1, shopService.getById(1), 10L);
    }

    @Test
    void testRedisIdWork() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWork.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit( task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }


    @Test
    public void testRedissonLockMethod1() throws InterruptedException {
        RLock lock = redissonClient.getLock("anyLock");
        boolean islock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!islock) {
            log.info("获取锁失败");
            return;
        }
        try{
            log.info("获取锁成功");
            testRedissonLockMethod2(lock);
            log.info("开始执行业务");
        }finally {
            log.warn("释放锁");
            lock.unlock();
        }
    }

void testRedissonLockMethod2(RLock lock){
        boolean islock = lock.tryLock();
        if (!islock) {
            log.info("获取锁失败 ----- 2");
            return;
        }
        try{
            log.info("获取锁成功 ---- 2");
            log.info("开始执行业务  ----- 2");
        }finally {
            log.warn("释放锁  ------ 2");
            lock.unlock();
        }
    }

    @Test
    public void loadShopData() {
        //1. 首先获取所有的店铺信息
        List<Shop> list = shopService.list();
        //2. 直接吧店铺分组，按照typeId分组，typeId一致的放在一个集合中
        Map<Long, List<Shop>> collect = list.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        //3. 分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : collect.entrySet()) {
            //3.1 获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2 获取相同类型的店铺列表
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            //3.3 写入redis
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        //统计
        System.out.println(stringRedisTemplate.opsForHyperLogLog().size("hl2"));
    }
}
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClinet;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWork;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
}
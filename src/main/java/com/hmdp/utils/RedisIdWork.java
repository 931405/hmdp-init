package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class RedisIdWork {

    // 开始时间戳
    private static final  long BEGIN_STAMP = 1020387661L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPerfix){
        //计算时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = second - BEGIN_STAMP;
        //redis自增ID
        //获取当前日期
        String nowDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Long l = stringRedisTemplate.opsForValue().increment("icr" + keyPerfix + nowDate);
        //拼接返回

        return  l << 32 | timeStamp;
    }


}

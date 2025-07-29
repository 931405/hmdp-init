package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisShop {
    public LocalDateTime exprireTime;
    public Object data;
}

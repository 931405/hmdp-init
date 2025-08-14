package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //首先需要校验手机号是否符合规范
        boolean invalid = RegexUtils.isPhoneInvalid(phone);
        //如果不符合，返回错误信息
        if(invalid){
            return Result.fail("手机号格式错误");
        }
        //如果符合，生成验证码
        String code  = RandomUtil.randomNumbers(6);
        //保存验证码到session之中
        //优化之后保存在redis之中
        //session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone , code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码为：{}", code);
        //返回
        return Result.ok();
    }


    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //首先还是验证手机号
        String phone = loginForm.getPhone();
        boolean invalid = RegexUtils.isPhoneInvalid(phone);
        //如果不符合，返回错误信息
        if(invalid){
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        //这里的验证码从redis中获取
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone).toString();
        String code = loginForm.getCode();
        if(!cacheCode.equals(code)){
            //验证码不一致报错
            return Result.fail("验证码错误");
        }
        //一致，根据手机号查询用户
        User user = lambdaQuery().eq(User::getPhone,phone).one();
        //如果不存在，创建一个新用户
        if (user == null) {
            user = createUser(phone);
        }
        //使用UUID来生成token
        String token = UUID.randomUUID().toString(true);
        log.info(token);
        //将User对象转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor(
                        (fieldName,fieldValue)->
                                fieldValue.toString()
                ));

        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token , userMap);

        //设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);

        //存在，保存用户信息到session
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //返回
    }

    @Override
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key sign:userId:yyyyMM
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + date;
        //4. 判断当前日期是否签到
        Boolean isSign = stringRedisTemplate.opsForValue().getBit(key, now.getDayOfMonth() - 1);
        if (BooleanUtil.isTrue(isSign)){
            return Result.fail("请勿重复签到");
        }
        //5. 如果未签到，写入数据，并返回成功
        stringRedisTemplate.opsForValue().setBit(key, now.getDayOfMonth() - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key sign:userId:yyyyMM
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + date;
        //4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5. 获取本月截至今天的所有签到信息  BITFIELD key get u13 0
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        log.info("results: {}", results.get(0));
        //6. 解析结果
        if(results.isEmpty()){
            return Result.ok(0);
        }
        Long l = results.get(0);
        if (l == null || l == 0) {
            return Result.ok(0);
        }
        int count = 0;
        //7. 进行统计
        //说明未签到,结束
        while ((l & 1) != 0) {
            count++;
            l = l >> 1;
            log.info("l: {}", l);
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}

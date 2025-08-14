package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long id, Boolean isFollow) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2. 查询是否关注
        if(isFollow){
            //2.1 如果true，进行关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess){
                //插入redis数据
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        }else{
            //2.2 如果false，进行取消关注 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isfollow(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询是否关注 select count from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> ids = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //解析ids
        if(ids == null || ids.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> list = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> userDTOS = userService.listByIds(list).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}

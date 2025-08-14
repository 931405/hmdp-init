package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryUserId(blog);
            this.isBlogLike( blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryById(Long id) {
        //根据id查询
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("数据不存在");
        }
        queryUserId(blog);
        isBlogLike(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.判断是否点赞过
        String key = "blog:like:" + id;
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score ==  null) {
            //2.如果未点赞过，则点赞
            //2.1更新数据库的liked字段
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (BooleanUtil.isTrue( isSuccess)) {
                //2.2更新redis的set
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else{
            //2.如果点赞过，则不能再次点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (BooleanUtil.isTrue( isSuccess)) {
                //2.2更新redis的set
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //首先查询出来TOP5
        String key = "blog:like:" + id;
        Set<String> likes = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(likes == null || likes.isEmpty()){
            return Result.ok();
        }
        //解析用户id
        List<Long> list = likes.stream().map(Long::valueOf).toList();
        String s = StrUtil.join(",",list);
        //根据id查询用户
        List<UserDTO> userDTOS = userService.query().in("id" , list).last("ORDER BY FIELD(id,"+ s +")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //Collections.reverse(userDTOS);
        log.info("userDTOS:{}",userDTOS);
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1. 获取登陆用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2. 保存笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("发布笔记失败");
        }
        //3. 获取粉丝列表
        List<Follow> followUserIds = followService.query().eq("follow_user_id", user.getId()).list();
        if(followUserIds == null || followUserIds.isEmpty()){
            return Result.ok(blog.getId());
        }
        for (Follow followUserId : followUserIds) {
            Long followUserIdId = followUserId.getUserId();
            String key = "blog:receive:id:" + followUserIdId;
            //保存到redis当中
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:receive:id:" + userId;
        //2. 查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3. 解析数据: blogId, minTime, offset
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int offset1 = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            blogIds.add(Long.valueOf(typedTuple.getValue()));
            if(minTime == typedTuple.getScore().longValue()){
                offset1++;
            }else{
                minTime = typedTuple.getScore().longValue();
                offset1 = 1;
            }
        }
        log.info("blogIds:{},minTime:{},offset:{}", blogIds, minTime, offset1);
        //4. 根据id来查询笔记
        String s = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + s + ")").list();
        // 5. 封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offset1);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void queryUserId(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLike(Blog blog){
        if(UserHolder.getUser() == null){
            return;
        }
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score("blog:like:" + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }
}

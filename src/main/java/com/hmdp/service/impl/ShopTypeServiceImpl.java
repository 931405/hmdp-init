package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static cn.hutool.json.JSONUtil.toJsonStr;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringredisTemplate;
    /**
     * 使用redis实现店铺类型列表查询
     * @return
     */
    @Override
    public List<ShopType> queryList() {
//        List<String> list = stringredisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
//        if(list != null && list.size() > 0){
//            List<ShopType> types = list.stream().map(json -> JSONUtil.toBean(json, ShopType.class))
//                    .collect(Collectors.toList());
//            return types;
//        }
//        List<ShopType> shoptypes = query().orderByAsc("sort").list();
//        String shoptype = toJsonStr(shoptypes);
//        stringredisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY,shoptype);
//        return shoptypes;
        String s = stringredisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        if(StrUtil.isNotBlank(s)){
            return JSONUtil.toList(JSONUtil.parseArray( s),ShopType.class);
        }
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //此次使用String类型存储
        String types = toJsonStr(shopTypes);
        stringredisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,types);
        return shopTypes;
    }
}

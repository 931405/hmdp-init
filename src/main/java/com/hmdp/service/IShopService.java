package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 使用redis实现商铺查询
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 使用redis实现商铺更新
     * @param shop
     * @return
     */
    Result updateByShopId(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}

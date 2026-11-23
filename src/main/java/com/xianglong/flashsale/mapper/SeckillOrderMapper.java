package com.xianglong.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianglong.flashsale.domain.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /** 该活动已成交订单数 —— 缓存重建时用它反推真实库存 */
    @Select("SELECT COUNT(*) FROM seckill_order WHERE seckill_id = #{id}")
    int countBySeckill(@Param("id") Long id);

    /** 该活动所有成交用户 —— 缓存重建时回填幂等集合 */
    @Select("SELECT user_id FROM seckill_order WHERE seckill_id = #{id}")
    List<Long> selectUserIds(@Param("id") Long id);
}

package com.xianglong.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianglong.flashsale.domain.Reconcile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReconcileMapper extends BaseMapper<Reconcile> {

    /** 已预扣库存但尚未落库的异常单数 —— 重建库存时必须扣掉，否则会超卖 */
    @Select("SELECT COUNT(*) FROM reconcile WHERE seckill_id = #{id} AND resolved = 0")
    int countUnresolved(@Param("id") Long id);
}

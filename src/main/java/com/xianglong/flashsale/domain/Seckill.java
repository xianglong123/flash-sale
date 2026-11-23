package com.xianglong.flashsale.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill")
public class Seckill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Integer totalStock;
    private Integer availableStock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private Integer version;
}

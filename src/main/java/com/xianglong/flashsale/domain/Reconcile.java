package com.xianglong.flashsale.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reconcile")
public class Reconcile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long seckillId;
    private String reason;
    private String traceId;
    private Integer retryCount;
    private Integer resolved;
    private LocalDateTime createdAt;
}

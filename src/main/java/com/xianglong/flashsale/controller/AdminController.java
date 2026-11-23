package com.xianglong.flashsale.controller;

import com.xianglong.flashsale.common.BizException;
import com.xianglong.flashsale.common.ErrorCode;
import com.xianglong.flashsale.common.Result;
import com.xianglong.flashsale.domain.Seckill;
import com.xianglong.flashsale.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SeckillService seckillService;

    @PostMapping("/seckill")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String itemName = String.valueOf(body.getOrDefault("itemName", "默认商品"));
        if (itemName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "itemName 不能为空");
        }
        BigDecimal price = toDecimal(body.get("price"), new BigDecimal("5999.00"));
        Integer totalStock = toInt(body.get("totalStock"));
        Integer duration = toInt(body.get("durationSeconds"));

        Seckill s = seckillService.create(itemName, price, totalStock, duration);
        return Result.ok("CREATED", Map.of(
                "seckillId", s.getId(),
                "status", s.getStatus(),
                "totalStock", s.getTotalStock(),
                "startTime", s.getStartTime(),
                "endTime", s.getEndTime()
        ));
    }

    @PutMapping("/seckill/{id}/stock")
    public Result<Map<String, Object>> updateStock(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer totalStock = toInt(body.get("totalStock"));
        if (totalStock == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "totalStock 必填");
        }
        Seckill s = seckillService.updateStock(id, totalStock);
        return Result.ok("OK", Map.of(
                "seckillId", s.getId(),
                "totalStock", s.getTotalStock(),
                "availableStock", seckillService.currentStock(s),
                "status", s.getStatus()
        ));
    }

    @PostMapping("/seckill/{id}/start")
    public Result<Map<String, Object>> start(@PathVariable Long id) {
        Seckill s = seckillService.start(id);
        return Result.ok("OK", Map.of(
                "seckillId", s.getId(),
                "status", s.getStatus(),
                "startTime", s.getStartTime(),
                "endTime", s.getEndTime()
        ));
    }

    @PostMapping("/seckill/{id}/end")
    public Result<Map<String, Object>> end(@PathVariable Long id) {
        Seckill s = seckillService.end(id);
        return Result.ok("OK", Map.of("seckillId", s.getId(), "status", s.getStatus()));
    }

    @GetMapping("/seckill")
    public Result<List<Map<String, Object>>> list() {
        List<Map<String, Object>> data = seckillService.list().stream().map(s -> Map.<String, Object>of(
                "seckillId", s.getId(),
                "status", s.getStatus(),
                "totalStock", s.getTotalStock(),
                "availableStock", seckillService.currentStock(s),
                "startTime", s.getStartTime(),
                "endTime", s.getEndTime()
        )).toList();
        return Result.ok("OK", data);
    }

    private static Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数字格式不合法");
        }
    }

    private static BigDecimal toDecimal(Object v, BigDecimal def) {
        if (v == null) {
            return def;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "价格格式不合法");
        }
    }
}

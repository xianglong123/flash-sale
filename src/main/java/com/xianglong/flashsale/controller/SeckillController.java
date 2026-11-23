package com.xianglong.flashsale.controller;

import com.xianglong.flashsale.common.BizException;
import com.xianglong.flashsale.common.ErrorCode;
import com.xianglong.flashsale.common.Result;
import com.xianglong.flashsale.domain.SeckillOrder;
import com.xianglong.flashsale.service.OrderService;
import com.xianglong.flashsale.service.SeckillService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final OrderService orderService;

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok("OK", seckillService.detail(id));
    }

    @GetMapping("/{id}/stats")
    public Result<Map<String, Object>> stats(@PathVariable Long id) {
        return Result.ok("OK", seckillService.stats(id));
    }

    @PostMapping("/{id}/order")
    public Result<Map<String, Object>> order(@PathVariable Long id,
                                             @RequestHeader(value = "X-User-Id", required = false) String userId,
                                             HttpServletRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少 X-User-Id");
        }
        if (!userId.matches("\\d{1,20}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "X-User-Id 必须是数字");
        }

        Long uid = Long.valueOf(userId);
        String ip = clientIp(request);
        SeckillOrder order = orderService.create(uid, id, ip);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", order.getStatus());
        data.put("amount", order.getAmount());
        return Result.ok("OK", data);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

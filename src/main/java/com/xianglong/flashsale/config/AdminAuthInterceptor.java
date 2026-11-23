package com.xianglong.flashsale.config;

import com.xianglong.flashsale.common.BizException;
import com.xianglong.flashsale.common.ErrorCode;
import com.xianglong.flashsale.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final SeckillProperties props;
    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = props.getAdminToken();
        if (expected == null || expected.isBlank()) {
            // fail-fast 在启动时已拦过一次，这里只做兜底
            throw new BizException(ErrorCode.SYSTEM_BUSY, "管理端未配置");
        }

        String given = request.getHeader(HEADER);
        if (given == null || given.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // 定长比较，避免时序侧信道（requirement 7.4 第 2 条）
        boolean equal = MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
        if (!equal) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (!rateLimitService.allow("admin:rate:global", 30, 60)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }

        log.info("[ADMIN] {} {} by={}", request.getMethod(), request.getRequestURI(), tokenHash(expected));
        return true;
    }

    /** 审计只记录 token 的 sha256 前 8 位 */
    public static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}

package com.xianglong.flashsale.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> onBiz(BizException e) {
        Result<Object> body = Result.fail(e.getCode().name(), e.getMessage());
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        body.setData(e.getData());
        return ResponseEntity.status(e.getCode().status()).body(body);
    }

    /** 唯一索引兜底：并发下重复插入必须翻译成业务码，不能冒到 500 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Object>> onDuplicate(DuplicateKeyException e) {
        Result<Object> body = Result.fail(ErrorCode.ALREADY_PARTICIPATED.name(), ErrorCode.ALREADY_PARTICIPATED.message());
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        return ResponseEntity.status(ErrorCode.ALREADY_PARTICIPATED.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> onInvalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("参数不合法");
        Result<Object> body = Result.fail(ErrorCode.BAD_REQUEST.name(), msg);
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.status()).body(body);
    }

    /** 路径变量类型不匹配（如 seckillId=abc）必须是 400，不能冒 500 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> onTypeMismatch(Exception e) {
        Result<Object> body = Result.fail(ErrorCode.BAD_REQUEST.name(), "路径参数格式不合法");
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.status()).body(body);
    }

    /**
     * P0-001 修复点：缓存层不可用属于「依赖降级」，必须返回 503 让客户端稍后重试，
     * 而不是 500（500 的语义是服务端 bug，会误导调用方和告警）。
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<Object>> onDependencyDown(Exception e) {
        log.error("[DEGRADE] 依赖访问失败，降级为 503: {}", e.toString());
        Result<Object> body = Result.fail(ErrorCode.SYSTEM_NOT_READY.name(), "缓存层不可用，请稍后重试");
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        return ResponseEntity.status(ErrorCode.SYSTEM_NOT_READY.status()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> onOther(Exception e) {
        log.error("[FAIL] unexpected error", e);
        Result<Object> body = Result.fail(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.message());
        body.setTraceId(MDC.get(TraceIdFilter.TRACE));
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status()).body(body);
    }
}

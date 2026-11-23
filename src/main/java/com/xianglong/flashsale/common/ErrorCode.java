package com.xianglong.flashsale.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "活动不存在"),
    SOLD_OUT(HttpStatus.CONFLICT, "已售罄"),
    ALREADY_PARTICIPATED(HttpStatus.CONFLICT, "已参与"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    NOT_IN_WINDOW(HttpStatus.FORBIDDEN, "不在活动时间窗内"),
    SYSTEM_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "系统繁忙"),
    SYSTEM_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "活动未就绪"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未授权"),
    STOCK_DECREASE_FORBIDDEN(HttpStatus.BAD_REQUEST, "不允许减少库存"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "参数不合法"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统错误");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}

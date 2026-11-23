package com.xianglong.flashsale.common;

import lombok.Data;

@Data
public class Result<T> {
    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(String code, T data) {
        Result<T> r = new Result<>();
        r.code = code;
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}

package com.xianglong.flashsale.common;

public class BizException extends RuntimeException {
    private final ErrorCode code;
    private final Object data;

    public BizException(ErrorCode code) {
        this(code, code.message(), null);
    }

    public BizException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public BizException(ErrorCode code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public ErrorCode getCode() { return code; }
    public Object getData() { return data; }
}

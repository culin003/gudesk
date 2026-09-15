package com.gudesk.common.spi;

/**
 * 适配器运行异常：生命周期失败、平台能力缺失、编解码错误等场景统一抛出。
 */
public class AdapterException extends RuntimeException {

    public AdapterException(String message) {
        super(message);
    }

    public AdapterException(String message, Throwable cause) {
        super(message, cause);
    }

    public AdapterException(Throwable cause) {
        super(cause);
    }
}

package com.lumenforge.provider;

/**
 * 图片生成失败。消息是给用户直接看的完整中文文案（含「怎么办」的提示），
 * 上层只负责把它写进任务失败原因，不再二次加工。
 */
public class ImageGenException extends RuntimeException {
    private final Integer code;
    private final Object raw;

    public ImageGenException(String message) {
        this(message, null, null);
    }

    public ImageGenException(String message, Integer code, Object raw) {
        super(message);
        this.code = code;
        this.raw = raw;
    }

    public Integer getCode() { return code; }
    public Object getRaw() { return raw; }
}

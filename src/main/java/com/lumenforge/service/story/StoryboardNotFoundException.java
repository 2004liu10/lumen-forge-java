package com.lumenforge.service.story;

/**
 * 剧本不存在：Python 版这里回 404（{@code not_found("这份剧本不存在")}），
 * 与「参数不对」的 400 是两回事，所以单独一个异常，由控制器翻译成 404。
 */
public class StoryboardNotFoundException extends RuntimeException {

    public StoryboardNotFoundException() {
        super("这份剧本不存在");
    }
}

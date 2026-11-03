package com.lumenforge.entity.ncanvas;

/**
 * 上传内容超过上限时抛出。
 *
 * <p>对应 Python 版 {@code app/api/deps.py:66} 的 {@code raw_body(request, limit=...)}：
 * 超限时抛 {@code fail(413, "上传内容太大（x.xMB），上限 NMB")}。
 */
public class NodeCanvasTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NodeCanvasTooLargeException(String message) {
        super(message);
    }
}

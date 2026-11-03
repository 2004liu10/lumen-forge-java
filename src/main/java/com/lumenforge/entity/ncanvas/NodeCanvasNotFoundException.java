package com.lumenforge.entity.ncanvas;

/**
 * 找不到节点画布 / 素材时抛出的异常。
 *
 * <p>对应 Python 版 {@code app/api/node_canvas_api.py} 里 {@code HttpError(404, "节点画布不存在")}
 * 与 {@code HttpError(404, "素材不存在")}：业务层不直接拼 HTTP 状态，交给
 * {@code NodeCanvasExceptionHandler} 统一翻成 404。
 */
public class NodeCanvasNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NodeCanvasNotFoundException(String message) {
        super(message);
    }
}

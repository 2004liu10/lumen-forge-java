package com.lumenforge.entity.ncanvas;

/**
 * 节点画布的落盘失败（磁盘满、目录不可写等）。
 *
 * <p>对应 Python 版业务层抛出的 {@code RuntimeError}：控制器层把
 * {@code RuntimeError → 500}，并且把原始消息带回给前端。
 */
public class NodeCanvasStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NodeCanvasStorageException(String message) {
        super(message);
    }

    public NodeCanvasStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

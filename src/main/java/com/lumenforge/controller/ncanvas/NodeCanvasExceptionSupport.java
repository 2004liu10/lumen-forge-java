package com.lumenforge.controller.ncanvas;

import com.lumenforge.entity.ncanvas.NodeCanvasNotFoundException;
import com.lumenforge.entity.ncanvas.NodeCanvasStorageException;
import com.lumenforge.entity.ncanvas.NodeCanvasTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点画布控制器的异常映射基类。
 *
 * <p>原版控制器里有个 {@code _safe()}：{@code LookupError} → 404、{@code ValueError} → 400、
 * {@code RuntimeError} → 500，响应体统一是 {@code {"error": "..."}}。Java 这边 400 由公共的
 * {@code GlobalExceptionHandler} 处理（{@code IllegalArgumentException}），但它没有 404 / 413
 * 的对应项，而且它带着一个 {@code @ExceptionHandler(Exception.class)} 兜底 —— 如果本模块也写
 * 一个全局 {@code @RestControllerAdvice}，Spring 只会挑「先被解析到」的那个 advice，
 * 兜底处理器有可能把 404 抢成 500。所以这里不走 advice，而是把处理器挂在控制器自己的
 * 继承链上：控制器内声明的处理器优先级最高，不受 advice 注册顺序影响。
 */
public abstract class NodeCanvasExceptionSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeCanvasExceptionSupport.class);

    /** 找不到画布 / 素材 / 对话。 */
    @ExceptionHandler(NodeCanvasNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NodeCanvasNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(exception.getMessage()));
    }

    /** 上传体积超限。 */
    @ExceptionHandler(NodeCanvasTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(NodeCanvasTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error(exception.getMessage()));
    }

    /** 落盘 / 读盘失败。 */
    @ExceptionHandler(NodeCanvasStorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorage(NodeCanvasStorageException exception) {
        LOGGER.error("节点画布存储失败", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(exception.getMessage()));
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", message == null ? "" : message);
        return body;
    }
}

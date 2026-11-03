package com.lumenforge.common.api;

import com.lumenforge.security.CredentialKeyNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把服务异常转换为前端能识别的 HTTP 状态和 {error} 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 把缺少加密配置反馈成可处理的服务配置错误，避免前端只看到笼统 500。 */
    @ExceptionHandler(CredentialKeyNotConfiguredException.class)
    public ResponseEntity<java.util.Map<String, String>> handleMissingCredentialKey(CredentialKeyNotConfiguredException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(java.util.Collections.singletonMap("error",
                        "账号凭据加密密钥未配置。请在 IntelliJ 启动配置中设置 APP_CREDENTIAL_KEY 并重启应用。"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<java.util.Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Collections.singletonMap("error", exception.getMessage()));
    }

    /**
     * 方法用错（例如只支持 POST 的端点被 GET）应回 405 而不是 500，
     * 否则前端冒烟会看到一堆「Unexpected server error」，掩盖真正的问题。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<java.util.Map<String, String>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        String message = "这个接口不支持 " + exception.getMethod() + " 方法";
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(java.util.Collections.singletonMap("error", message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<java.util.Map<String, String>> handleMissingParameter(MissingServletRequestParameterException exception) {
        String message = "缺少参数：" + exception.getParameterName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Collections.singletonMap("error", message));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<java.util.Map<String, String>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(java.util.Collections.singletonMap("error", "不支持的 Content-Type：" + exception.getContentType()));
    }

    /**
     * 请求体不是合法 JSON（或字段类型对不上）时，Python 版 deps.json_body 回 400，
     * 这里必须同样回 400，否则前端只会看到笼统的 500「Unexpected server error」。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<java.util.Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Collections.singletonMap("error", "请求体不是合法的 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<java.util.Map<String, String>> handleUnexpected(Exception exception) {
        // 详细堆栈只写入服务端日志，帮助定位数据库/外部接口故障；HTTP 响应不回显敏感内部信息。
        LOGGER.error("Unhandled API request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(java.util.Collections.singletonMap("error", "Unexpected server error"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<java.util.Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "Invalid request"
                : exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(java.util.Collections.singletonMap("error", message));
    }
}





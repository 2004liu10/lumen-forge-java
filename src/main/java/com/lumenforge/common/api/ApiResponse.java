package com.lumenforge.common.api;

import java.time.Instant;

public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;
    private final Instant timestamp;

    private ApiResponse(boolean success, T data, String message, Instant timestamp) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.timestamp = timestamp;
    }

    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}





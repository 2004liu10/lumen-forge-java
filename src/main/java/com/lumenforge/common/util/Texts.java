package com.lumenforge.common.util;

import java.util.Map;

/**
 * 请求体字段读取工具。
 *
 * <p>控制器一律把请求体收成 {@code Map<String, Object>}，这里集中处理「缺字段、类型不对、
 * 空字符串」三种常见情况，避免每个 service 各写一遍强转。所有方法对 {@code null} 安全。
 */
public final class Texts {

    private Texts() {
    }

    /** 去掉首尾空白；{@code null} 转成空串。 */
    public static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 取字符串字段，缺省返回 {@code fallback}。 */
    public static String str(Map<String, Object> body, String key, String fallback) {
        if (body == null) {
            return fallback;
        }
        Object value = body.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    /** 取字符串字段并去空白，缺省返回空串。 */
    public static String str(Map<String, Object> body, String key) {
        return trim(body == null ? null : body.get(key));
    }

    /** 请求体里是否显式带了这个键（哪怕是空串），用于区分「不改」与「清空」。 */
    public static boolean has(Map<String, Object> body, String key) {
        return body != null && body.containsKey(key);
    }

    /** 取整型，无法解析时用 {@code fallback}。 */
    public static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static int asInt(Map<String, Object> body, String key, int fallback) {
        return asInt(body == null ? null : body.get(key), fallback);
    }

    /** 取长整型，无法解析时用 {@code fallback}。 */
    public static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 取布尔值；字符串 "false"/"0"/"no" 视作假。 */
    public static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    public static boolean asBool(Map<String, Object> body, String key, boolean fallback) {
        return asBool(body == null ? null : body.get(key), fallback);
    }

    /** 按码点安全截断，避免把 emoji 从中间劈开。 */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }
}

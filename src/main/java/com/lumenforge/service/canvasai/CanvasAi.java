package com.lumenforge.service.canvasai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 画布 AI 两个模块（编导 / 聊天）共用的小工具。
 *
 * <p>对应 Python 版里散落的几个小函数：
 * {@code canvas_director._text} / {@code canvas_chat._text} / {@code _punct_strip} /
 * {@code _clean} / {@code round()}。抽出来是为了让两边的截断与取整口径一模一样 ——
 * Python 的 {@code round()} 是「四舍六入五取偶」（banker's rounding），
 * 直接用 {@link Math#round(double)} 会在 6.5 这种值上多算一镜。
 */
public final class CanvasAi {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Python 的 {@code re.sub(r"[\s\W_]+", "", value, flags=re.UNICODE)}：
     * 去掉空白与标点，保留汉字 / 字母 / 数字。
     *
     * <p>必须带 {@link Pattern#UNICODE_CHARACTER_CLASS}，否则 Java 的 {@code \w}
     * 只认 ASCII，中文会被当成 {@code \W} 一起删掉。
     */
    private static final Pattern PUNCT = Pattern.compile("[\\s\\W_]+", Pattern.UNICODE_CHARACTER_CLASS);

    private CanvasAi() {
    }

    /**
     * Python {@code canvas_director._text}：{@code None} 转空串，Map/List 转 JSON 文本，
     * 其余 {@code str()}，然后 {@code strip()[:max]}。
     */
    public static String text(Object value, int max) {
        String raw;
        if (value == null) {
            raw = "";
        } else if (value instanceof Map || value instanceof Collection) {
            try {
                raw = JSON.writeValueAsString(value);
            } catch (Exception err) {
                raw = String.valueOf(value);
            }
        } else {
            raw = String.valueOf(value);
        }
        return slice(raw.trim(), max);
    }

    /** Python {@code canvas_chat._text}：{@code None} 转空串，其余 {@code str().strip()[:max]}。 */
    public static String plainText(Object value, int max) {
        if (value == null) {
            return "";
        }
        return slice(String.valueOf(value).trim(), max);
    }

    /** {@code str[:max]} —— 超过就截断，不足原样返回。 */
    public static String slice(String value, int max) {
        if (value == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** JS 的 {@code Array.isArray(x) ? x : []}；返回只读视图，不修改入参。 */
    public static List<?> asList(Object value) {
        if (value instanceof List) {
            return (List<?>) value;
        }
        return new ArrayList<Object>();
    }

    /** Python {@code round()}：整数部分为偶数时 .5 向下，奇数时 .5 向上。 */
    public static long pyRound(double value) {
        double floor = Math.floor(value);
        double diff = value - floor;
        if (diff > 0.5) {
            return (long) floor + 1L;
        }
        if (diff < 0.5) {
            return (long) floor;
        }
        long whole = (long) floor;
        return (whole % 2L == 0L) ? whole : whole + 1L;
    }

    /** Python {@code float(x)}；解析不出来按 0.0（Python 会抛 ValueError，这里选择容错）。 */
    public static double number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException err) {
            return 0.0;
        }
    }

    /** Python 的 {@code float(x or fallback)}：空串 / 0 / null 都当没给。 */
    public static double numberOr(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            double num = ((Number) value).doubleValue();
            return num == 0.0 ? fallback : num;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "0".equals(text)) {
            return fallback;
        }
        try {
            double num = Double.parseDouble(text);
            return num == 0.0 ? fallback : num;
        } catch (NumberFormatException err) {
            return fallback;
        }
    }

    /** Python 的 {@code _punct_strip} / {@code _clean}：去空白与标点。 */
    public static String punctStrip(String value) {
        return PUNCT.matcher(value == null ? "" : value).replaceAll("");
    }

    /** {@code sep.join(parts)}，空元素跳过（Python 侧调用前都已过滤过）。 */
    public static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.toString();
    }
}

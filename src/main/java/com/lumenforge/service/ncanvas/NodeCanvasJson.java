package com.lumenforge.service.ncanvas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点画布的请求体 / 响应体编解码。
 *
 * <p>复刻 Python 版 {@code app/api/deps.py} 的 {@code json_body()}：
 * 空体当 {@code {}}、解析失败报「请求体不是合法的 JSON」、数组包成 {@code {"__array__": [...]}}、
 * 其它非对象报「请求体必须是 JSON 对象」。
 *
 * <p>为什么要自己解析而不是直接用 {@code @RequestBody Map}：Spring 的
 * {@code HttpMessageNotReadableException} 会吐它自己的英文报错，而这里必须一字不差地
 * 回中文文案，前端只认这几句。
 */
public final class NodeCanvasJson {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Python {@code datetime.now().astimezone().isoformat(timespec="milliseconds")} 的等价格式。 */
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private NodeCanvasJson() {
    }

    /** 解析 JSON 请求体；{@code raw} 为 {@code null} 或空串时返回空对象。 */
    public static Map<String, Object> readBody(ObjectMapper mapper, String raw) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // 只有「真的一条都没有」才当空体：原版判的是 `if not raw`，
        // 一个空格也算有内容，解析失败就该报 400，不能悄悄当成 {}
        if (raw == null || raw.isEmpty()) {
            return body;
        }
        Object parsed;
        try {
            parsed = mapper.readValue(raw, Object.class);
        } catch (IOException err) {
            throw new IllegalArgumentException("请求体不是合法的 JSON");
        }
        if (parsed instanceof List) {
            body.put("__array__", parsed);
            return body;
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) parsed;
        return object;
    }

    /** 序列化成 UTF-8 JSON 文本。 */
    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException err) {
            throw new IllegalArgumentException("无法序列化画布数据：" + err.getMessage());
        }
    }

    public static byte[] writeBytes(ObjectMapper mapper, Object value) {
        return write(mapper, value).getBytes(UTF8);
    }

    /** 带毫秒、带本地时区偏移的 ISO 8601（导出的 {@code exportedAt}）。 */
    public static String isoNow() {
        return ZonedDateTime.now().format(ISO_MILLIS);
    }

    /** RFC 5987 的 {@code filename*=UTF-8''...} 编码（连 {@code /} 一起编码）。 */
    public static String quoteRfc5987(String value) {
        return NodeCanvasFiles.percentEncode(value == null ? "" : value);
    }

    /** 把请求体里的值读成 {@code Map}，不是对象时返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** 把请求体里的值读成 {@code List}，不是数组时返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    /** 新的有序映射（响应体字段顺序与原版一致）。 */
    public static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    /** 空列表，便于给 {@code sessions} / {@code items} 这类字段一个非 null 的默认值。 */
    public static List<Map<String, Object>> emptyRows() {
        return new ArrayList<Map<String, Object>>();
    }
}

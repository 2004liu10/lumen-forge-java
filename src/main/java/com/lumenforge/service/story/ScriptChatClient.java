package com.lumenforge.service.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Texts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「写剧本」用的 OpenAI 兼容对话客户端。
 *
 * <p>抽出来是为了让「分镜」与「节点画布 · AI 编导」共用同一份配置与同一套报错口径 ——
 * 两个入口都在写剧本，用户看到的错误提示就该一模一样。
 *
 * <p>重试策略照抄 Python 版：上游偶发 5xx / 网络抖动很常见（实测长分镜 prompt 下会间歇性 5xx），
 * 一次就抛给用户等于让整条编导白等一两分钟，所以 5xx 与网络错误重试两次；
 * 4xx（Key 不对、地址不对、内容违规）重试也没用，直接报。
 */
@Component
public class ScriptChatClient {

    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");
    private static final Pattern CHAT_COMPLETIONS_TAIL = Pattern.compile("/chat/completions$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern FENCE_HEAD = Pattern.compile("^```(?:json)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_TAIL = Pattern.compile("```$");

    /** 三次尝试之间等多久：先 1.2 秒，再 3 秒。 */
    private static final long RETRY_BACKOFF_MS_FIRST = 1200L;
    private static final long RETRY_BACKOFF_MS_SECOND = 3000L;
    /** 剩余预算低于这个值就不再开新的一轮，开了也来不及。 */
    private static final long MIN_LEFT_MS = 5000L;

    private final ObjectMapper json;

    @Autowired
    public ScriptChatClient(ObjectMapper json) {
        this.json = json;
    }

    // ============================================================== 请求

    /**
     * 一次对话请求，返回模型回的那段文本。
     *
     * @param cfg     {@code baseUrl} / {@code apiKey} / {@code model}（来自 {@link StoryModelStore}）
     * @param body    请求体（messages、temperature 等）
     * @param timeout 整轮（含重试）的时间预算，毫秒
     */
    public String postChat(Map<String, Object> cfg, Map<String, Object> body, int timeout) {
        String baseUrl = Texts.str(cfg, "baseUrl");
        String url = CHAT_COMPLETIONS_TAIL.matcher(baseUrl).find()
                ? baseUrl
                : baseUrl + "/chat/completions";
        long deadline = System.currentTimeMillis() + timeout;
        String apiKey = Texts.str(cfg, "apiKey");

        RuntimeException lastErr = null;
        Reply reply = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                sleep(attempt == 1 ? RETRY_BACKOFF_MS_FIRST : RETRY_BACKOFF_MS_SECOND);
            }
            long leftMs = deadline - System.currentTimeMillis();
            if (leftMs < MIN_LEFT_MS) {
                throw new IllegalArgumentException("模型一直没有响应（超过 "
                        + Math.round(timeout / 1000.0) + " 秒），换个模型或稍后再试");
            }
            int budget = (int) Math.max(1000L, leftMs);
            try {
                reply = send(url, apiKey, body, budget);
            } catch (SocketTimeoutException err) {
                throw new IllegalArgumentException("模型一直没有响应（超过 "
                        + Math.round(timeout / 1000.0) + " 秒），换个模型或稍后再试");
            } catch (IOException err) {
                // 连不上：网络抖动，重试
                lastErr = new IllegalArgumentException(messageOf(err));
                reply = null;
                continue;
            }
            if (reply.status >= 500) {
                lastErr = new IllegalArgumentException(explainHttpError(reply.status, reply.text));
                reply = null;
                continue; // 上游 5xx：重试
            }
            break;
        }

        if (reply == null) {
            throw lastErr != null ? lastErr
                    : new IllegalArgumentException("模型接口没有响应，稍后再试");
        }

        String text = reply.text;
        if (reply.status >= 400) {
            throw new IllegalArgumentException(explainHttpError(reply.status, text));
        }

        Object payload;
        try {
            payload = json.readValue(text, Object.class);
        } catch (IOException err) {
            throw new IllegalArgumentException("模型返回的不是标准 JSON，换个模型再试");
        }
        if (payload instanceof Map) {
            Object errObj = ((Map<?, ?>) payload).get("error");
            if (errObj != null && !asString(errObj).trim().isEmpty()) {
                String message;
                if (errObj instanceof Map) {
                    message = asString(((Map<?, ?>) errObj).get("message"));
                } else {
                    message = asString(errObj);
                }
                throw new IllegalArgumentException(message.isEmpty() ? "模型返回了错误" : message);
            }
        }

        String answer = contentOf(payload);
        if (answer.trim().isEmpty()) {
            throw new IllegalArgumentException("模型没返回内容，换个模型再试");
        }
        return answer;
    }

    /** 各家报错文案差别很大，统一成人话。 */
    public String explainHttpError(int status, String text) {
        String detail = truncate(WHITESPACE.matcher(text == null ? "" : text).replaceAll(" "), 200);
        String tail = detail.isEmpty() ? "" : "：" + detail;
        if (status == 401 || status == 403) {
            return "API Key 不对，或者这个 Key 没有调用该模型的权限（HTTP " + status + "）" + tail;
        }
        if (status == 404) {
            return "接口地址或模型 ID 不对，检查这两项有没有填错（HTTP 404）" + tail;
        }
        if (status == 429) {
            return "调用太频繁，或者免费额度用完了，等一会儿再试（HTTP 429）";
        }
        if (status >= 500) {
            return "模型服务端出错，稍后再试（HTTP " + status + "）";
        }
        return "写剧本失败（HTTP " + status + "）" + tail;
    }

    /**
     * 把模型返回的文本里那段 JSON 抠出来：免费小模型经常带 markdown 围栏或前后废话。
     *
     * @return 解析出的 Map / List；都失败返回 {@code null}
     */
    public Object extractJson(String text) {
        String raw = text == null ? "" : text.trim();
        raw = FENCE_HEAD.matcher(raw).replaceFirst("");
        raw = FENCE_TAIL.matcher(raw).replaceFirst("").trim();
        Object direct = tryParse(raw);
        if (direct != null) {
            return direct;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return tryParse(raw.substring(start, end + 1));
        }
        return null;
    }

    // ============================================================== HTTP 细节

    private Reply send(String url, String apiKey, Map<String, Object> body, int budgetMs)
            throws IOException {
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (IOException err) {
            throw new IllegalStateException("无法序列化请求体：" + err.getMessage(), err);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(budgetMs);
        connection.setReadTimeout(budgetMs);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        try {
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            int status = connection.getResponseCode();
            String text = readBody(connection, status);
            return new Reply(status, text);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                sink.write(buffer, 0, read);
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 已经关了
            }
        }
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }

    private String contentOf(Object payload) {
        if (!(payload instanceof Map)) {
            return "";
        }
        Object choices = ((Map<?, ?>) payload).get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
            return "";
        }
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) {
            return "";
        }
        Object message = ((Map<?, ?>) first).get("message");
        if (!(message instanceof Map)) {
            return "";
        }
        Object content = ((Map<?, ?>) message).get("content");
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List) {
            StringBuilder out = new StringBuilder();
            for (Object part : (List<?>) content) {
                if (part instanceof Map) {
                    out.append(asString(((Map<?, ?>) part).get("text")));
                }
            }
            return out.toString();
        }
        return "";
    }

    private Object tryParse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (IOException err) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String messageOf(Throwable err) {
        String message = err.getMessage();
        return message == null || message.trim().isEmpty() ? err.getClass().getSimpleName() : message;
    }

    /** 一次请求的结果：状态码 + 原始响应文本（成功和失败都要，报错时要带上游原文）。 */
    private static final class Reply {

        final int status;
        final String text;

        Reply(int status, String text) {
            this.status = status;
            this.text = text == null ? "" : text;
        }
    }

    /** 给调用方拼多模态 content 用的小工具：文字段 + 图段。 */
    public static List<Map<String, Object>> multimodal(String prompt, List<String> dataUris) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("type", "text");
        textPart.put("text", prompt == null ? "" : prompt);
        content.add(textPart);
        for (int i = 0; i < dataUris.size(); i++) {
            Map<String, Object> label = new LinkedHashMap<String, Object>();
            label.put("type", "text");
            label.put("text", "第 " + (i + 1) + " 张人物参考图：");
            content.add(label);
        }
        for (String uri : dataUris) {
            Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
            imageUrl.put("url", uri);
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("type", "image_url");
            part.put("image_url", imageUrl);
            content.add(part);
        }
        return content;
    }
}

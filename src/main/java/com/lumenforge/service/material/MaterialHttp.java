package com.lumenforge.service.material;

import com.lumenforge.common.util.Texts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 素材模块访问外网的唯一通道。
 *
 * <p>项目里没有引入任何 HTTP 客户端依赖（只有 spring-web），所以这里直接用
 * {@link HttpURLConnection}：够用、可控，而且超时语义能对齐 Python 版 httpx 的写法
 * （{@code FETCH_TIMEOUT_MS=20s} / {@code DOWNLOAD_TIMEOUT_MS=60s}）。
 *
 * <p>所有失败都抛 {@link IllegalArgumentException}，错误文案与 Python 版逐字一致 ——
 * 前端会把这些话原样弹给用户。
 */
final class MaterialHttp {

    private MaterialHttp() {
    }

    /** 一次请求的结果。 */
    static final class Response {
        final int status;
        final byte[] buffer;
        final String contentType;
        final String finalUrl;

        Response(int status, byte[] buffer, String contentType, String finalUrl) {
            this.status = status;
            this.buffer = buffer == null ? new byte[0] : buffer;
            this.contentType = contentType == null ? "" : contentType;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
        }

        /** 文本内容，按响应头里的 charset 解码（拿不到就 UTF-8）。 */
        String text() {
            Charset charset = charsetOf(contentType);
            return new String(buffer, charset);
        }
    }

    /**
     * GET 一个地址。
     *
     * @param url     完整地址
     * @param headers 附加请求头
     * @param timeout 超时（毫秒）
     * @param label   失败时的前缀文案，例如「请求接口失败」
     */
    static Response get(String url, Map<String, String> headers, int timeout, String label) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", MaterialConstants.USER_AGENT);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] buffer = stream == null ? new byte[0] : readAll(stream);
            String finalUrl = connection.getURL() == null ? url : connection.getURL().toString();
            return new Response(status, buffer, contentType, finalUrl);
        } catch (java.net.SocketTimeoutException err) {
            throw new IllegalArgumentException(label + "：超时（" + (timeout / 1000) + " 秒没响应）");
        } catch (IOException err) {
            throw new IllegalArgumentException(label + "：" + describe(err));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 抓文本；HTTP 状态 ≥400 直接抛错（文案与 Python 版一致）。 */
    static String fetchText(String url, Map<String, String> headers, int timeout, String label) {
        Response res = get(url, headers, timeout, label);
        if (res.status >= 400) {
            String body = res.text();
            throw new IllegalArgumentException(label + "：HTTP " + res.status
                    + (body.trim().isEmpty() ? "" : "：" + Texts.truncate(body.trim(), 120)));
        }
        return res.text();
    }

    /**
     * 把异常翻译成用户能看懂的一句。
     *
     * <p>Python 版直接把 httpx 的异常 str 拼进去，Java 这边挑最常见的两种（域名解析、
     * 连接被拒）说清楚，其余原样给 message。
     */
    private static String describe(IOException err) {
        String message = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
        if (err instanceof java.net.UnknownHostException) {
            return "连不上这个地址（域名解析失败）";
        }
        if (err instanceof java.net.ConnectException) {
            return "连不上这个地址（连接被拒绝）";
        }
        return message;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = stream.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关流失败不影响已经拿到的内容
            }
        }
        return out.toByteArray();
    }

    /** 从 content-type 里取 charset，取不到用 UTF-8。 */
    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int index = lower.indexOf("charset=");
            if (index >= 0) {
                String name = lower.substring(index + "charset=".length()).trim();
                int semicolon = name.indexOf(';');
                if (semicolon >= 0) name = name.substring(0, semicolon).trim();
                name = name.replace("\"", "").replace("'", "").trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // 不认识就退回 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 只保留 content-type 分号前的那段小写（Python 版就是这么比对的）。 */
    static String mimeOf(String contentType) {
        if (contentType == null) return "";
        String text = contentType;
        int semicolon = text.indexOf(';');
        if (semicolon >= 0) text = text.substring(0, semicolon);
        return text.trim().toLowerCase();
    }

    /** 常用请求头：UA + 可选 Referer。 */
    static Map<String, String> headers(String referer) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("User-Agent", MaterialConstants.USER_AGENT);
        if (referer != null && !referer.trim().isEmpty()) {
            out.put("Referer", referer.trim());
        }
        return out;
    }
}

package com.lumenforge.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 火山引擎 V4 签名（HMAC-SHA256）。
 *
 * <p>即梦的智能视觉接口不在 Bearer 体系里，必须按官方的 canonical request 规则自己签名。
 * 这里独立成类，便于用官方文档示例值做离线校验。</p>
 */
public final class VolcSigner {
    private VolcSigner() {}

    /** 生成 {@code x-date} 头：UTC 的 {@code %Y%m%dT%H%M%SZ}。 */
    public static String utcXDate() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /**
     * 计算 Authorization 头。
     *
     * @param query 查询参数（Action / Version），会按 key 排序并 RFC3986 编码
     */
    public static String authorization(String method, String host, String path, Map<String, String> query,
                                       String payload, String accessKeyId, String accessKeySecret,
                                       String region, String service, String xDate) {
        String canonicalQuery = canonicalQuery(query);

        // 固定只签 host 与 x-date 两个头
        String canonicalHeaders = "host:" + host + "\nx-date:" + xDate + "\n";
        String signedHeaders = "host;x-date";

        String canonicalRequest = method + "\n"
                + (path == null || path.isEmpty() ? "/" : path) + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + sha256Hex(payload);

        String date = xDate.substring(0, 8);
        String credentialScope = date + "/" + region + "/" + service + "/request";
        String stringToSign = "HMAC-SHA256\n" + xDate + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

        // 链式派生：每步 HMAC 输出的二进制原样作为下一步的 key
        byte[] kDate = hmac(accessKeySecret, date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "request");
        String signature = hex(hmac(kSigning, stringToSign));

        return "HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** 查询串：key 升序、key 与 value 都做 RFC3986 编码。 */
    public static String canonicalQuery(Map<String, String> query) {
        Map<String, String> sorted = new TreeMap<String, String>();
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                sorted.put(rfc3986Encode(entry.getKey()), rfc3986Encode(entry.getValue()));
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    /** RFC3986 编码：在 encodeURIComponent 基础上补齐 {@code !'()*} 的转义。 */
    public static String rfc3986Encode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
                    .replace("!", "%21")
                    .replace("'", "%27")
                    .replace("(", "%28")
                    .replace(")", "%29");
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 不可用", exception);
        }
    }

    public static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest((data == null ? "" : data).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public static byte[] hmac(String key, String data) {
        return hmac(key.getBytes(StandardCharsets.UTF_8), data);
    }

    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 不可用", exception);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    /** 便捷构造：Action + Version 两个查询参数。 */
    public static Map<String, String> actionQuery(String action, String version) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("Action", action);
        query.put("Version", version);
        return query;
    }
}

package com.lumenforge.provider;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * 下载到的图片字节与推荐扩展名。
 *
 * <p>扩展名优先按 Content-Type 判定，其次按 URL 后缀兜底 —— 扩展名错了浏览器照样能显示，
 * 但「下载」与「转视频」两条链路会按后缀判断类型，所以必须尽量准。</p>
 */
public class DownloadedImage {
    private final byte[] bytes;
    private final String extension;

    public DownloadedImage(byte[] bytes, String extension) {
        this.bytes = bytes;
        this.extension = extension;
    }

    public byte[] getBytes() { return bytes; }
    public String getExtension() { return extension; }

    /** 按 Content-Type 优先、URL 后缀兜底猜扩展名。 */
    public static String guessExtension(String url, String contentType) {
        String ctype = contentType == null ? "" : contentType.toLowerCase();
        if (ctype.contains("jpeg")) return ".jpg";
        if (ctype.contains("webp")) return ".webp";
        if (ctype.contains("png")) return ".png";
        try {
            String path = url == null ? "" : url;
            int mark = path.indexOf('?');
            if (mark >= 0) path = path.substring(0, mark);
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot).toLowerCase();
                if (".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext) || ".webp".equals(ext)) {
                    return ".jpeg".equals(ext) ? ".jpg" : ext;
                }
            }
        } catch (Exception ignored) {
            // 后缀解析失败不影响主流程，落到默认 .png
        }
        return ".png";
    }

    /** 把字节包成 base64（Cloudflare 直接回图片字节时用）。 */
    public static String toBase64(byte[] bytes) {
        ByteArrayOutputStream ignored = null;
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 图片字节 → data URI。 */
    public static String toDataUri(byte[] bytes, String mimeType) {
        String mime = mimeType == null || mimeType.trim().isEmpty() ? "image/png" : mimeType.trim().toLowerCase();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** {@code data:image/png;base64,xxx} → 纯 base64 部分；不是 data URI 时原样返回。 */
    public static String stripDataUri(String uri) {
        String value = uri == null ? "" : uri;
        int mark = value.indexOf(";base64,");
        if (value.startsWith("data:") && mark > 0) return value.substring(mark + ";base64,".length());
        return value;
    }

    /** {@code data:image/png;base64,xxx} → mime 部分；不是 data URI 时返回空串。 */
    public static String dataUriMime(String uri) {
        String value = uri == null ? "" : uri;
        if (!value.startsWith("data:")) return "";
        int mark = value.indexOf(";base64,");
        return mark > 0 ? value.substring("data:".length(), mark) : "";
    }
}

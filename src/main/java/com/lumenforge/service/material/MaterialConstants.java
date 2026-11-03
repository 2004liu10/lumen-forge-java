package com.lumenforge.service.material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材模块的常量表，逐条照抄 Python 版 {@code app/materials.py}。
 *
 * <p>这里的每一项都会被接口回显（图源列表、排行榜模式）或被抓取/下载逻辑用到，
 * 所以值必须与 Python 版逐字一致 —— 前端按 {@code id} 记住用户选过的源，
 * 改了 id 就等于把用户的偏好清空。
 */
public final class MaterialConstants {

    private MaterialConstants() {
    }

    /** 请求外部站点时统一用的 UA：很多素材站只认它。 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** 拉列表接口的超时（毫秒）。 */
    public static final int FETCH_TIMEOUT_MS = 20000;

    /** 下载素材的超时（毫秒）。 */
    public static final int DOWNLOAD_TIMEOUT_MS = 60000;

    /** 列表缓存有效期（毫秒）。 */
    public static final long CACHE_TTL_MS = 3L * 60L * 1000L;

    /** 图片搜索源（顺序即前端下拉顺序）。 */
    public static final List<Map<String, Object>> IMAGE_SEARCH_SOURCES;

    /** 排行榜模式。 */
    public static final List<Map<String, Object>> IMAGE_RANK_MODES;

    /** 视频类素材的扩展名。 */
    public static final List<String> VIDEO_EXTS = Arrays.asList("mp4", "mov", "webm", "m3u8");

    /** 图片类素材的扩展名。 */
    public static final List<String> IMAGE_EXTS = Arrays.asList("png", "jpg", "jpeg", "webp", "gif");

    /** 全部媒体扩展名。 */
    public static final List<String> MEDIA_EXTS;

    /** 扩展名 → MIME。 */
    public static final Map<String, String> MIME_BY_EXT;

    /** MIME → 扩展名。 */
    public static final Map<String, String> MIME_TO_EXT;

    /** 水印相关的键名片段，挑地址时先跳过。 */
    public static final String WATERMARK_HINT = "water|mark|wm|logo";

    /** 挑媒体地址时按 kind 尝试的键顺序。 */
    public static final Map<String, List<String>> URL_KEYS_BY_KIND;

    /** 封面候选键。 */
    public static final List<String> POSTER_KEYS;

    /** 标题候选键。 */
    public static final List<String> TITLE_KEYS;

    /** 快照（rand 地址背后的二进制）有效期。 */
    public static final long SNAPSHOT_TTL_MS = 30L * 60L * 1000L;

    /** 快照最多存几份。 */
    public static final int SNAPSHOT_MAX = 120;

    /** 单份快照的字节上限。 */
    public static final int SNAPSHOT_MAX_BYTES = 8 * 1024 * 1024;

    /** 短片编号允许的字符集（要拼进地址）。 */
    public static final String CLIP_ID_PATTERN = "^[\\w.-]{1,64}$";

    /** 搜索一次最多返回多少张。 */
    public static final int SEARCH_LIMIT_MAX = 48;

    /** 排行榜一次最多返回多少张。 */
    public static final int RANK_LIMIT_MAX = 48;

    /** 排行榜站点。 */
    public static final String PIXIVIC_RANK_API = "https://pix.ipv4.host/ranks";

    /** 排行榜日期缓存有效期。 */
    public static final long RANK_DATE_TTL_MS = 30L * 60L * 1000L;

    /** HLS 分片下载的总预算。 */
    public static final long HLS_BUDGET_MS = 180000L;

    /** HLS 最多合并多少个分片。 */
    public static final int HLS_MAX_SEGMENTS = 3000;

    /** HLS 单片超时。 */
    public static final int SEGMENT_TIMEOUT_MS = 20000;

    /** 每个分类记住多少条已见过的素材（用于「换一批」不重复）。 */
    public static final int SEEN_MAX = 600;

    static {
        Map<String, Object> so360 = new LinkedHashMap<String, Object>();
        so360.put("id", "so360");
        so360.put("name", "360 图片（国漫/游戏/动漫角色最全，中文直接搜）");
        Map<String, Object> pixiv = new LinkedHashMap<String, Object>();
        pixiv.put("id", "pixiv");
        pixiv.put("name", "Pixiv 插画（二次元同人，按标签搜，如 初音未来 / 少女）");
        Map<String, Object> booru = new LinkedHashMap<String, Object>();
        booru.put("id", "booru");
        booru.put("name", "动漫标签图站（英文 tag 最准，如 1girl white_hair）");
        Map<String, Object> bing = new LinkedHashMap<String, Object>();
        bing.put("id", "bing");
        bing.put("name", "必应图片（通用，描述性的话也能搜）");
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        sources.add(so360);
        sources.add(pixiv);
        sources.add(booru);
        sources.add(bing);
        IMAGE_SEARCH_SOURCES = Collections.unmodifiableList(sources);

        List<Map<String, Object>> modes = new ArrayList<Map<String, Object>>();
        modes.add(mode("day", "日榜"));
        modes.add(mode("week", "周榜"));
        modes.add(mode("month", "月榜"));
        modes.add(mode("male", "男性向榜"));
        modes.add(mode("female", "女性向榜"));
        IMAGE_RANK_MODES = Collections.unmodifiableList(modes);

        List<String> media = new ArrayList<String>(VIDEO_EXTS);
        media.addAll(IMAGE_EXTS);
        MEDIA_EXTS = Collections.unmodifiableList(media);

        Map<String, String> byExt = new LinkedHashMap<String, String>();
        byExt.put("mp4", "video/mp4");
        byExt.put("mov", "video/quicktime");
        byExt.put("webm", "video/webm");
        byExt.put("m3u8", "application/vnd.apple.mpegurl");
        byExt.put("ts", "video/mp2t");
        byExt.put("m4s", "video/iso.segment");
        byExt.put("png", "image/png");
        byExt.put("jpg", "image/jpeg");
        byExt.put("jpeg", "image/jpeg");
        byExt.put("webp", "image/webp");
        byExt.put("gif", "image/gif");
        MIME_BY_EXT = Collections.unmodifiableMap(byExt);

        Map<String, String> toExt = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : byExt.entrySet()) {
            if (!toExt.containsKey(entry.getValue())) {
                toExt.put(entry.getValue(), entry.getKey());
            }
        }
        MIME_TO_EXT = Collections.unmodifiableMap(toExt);

        Map<String, List<String>> keys = new LinkedHashMap<String, List<String>>();
        keys.put("video", Collections.unmodifiableList(Arrays.asList(
                "play_url", "video_url", "playurl", "video", "play", "url", "src", "file",
                "download_url", "link", "href")));
        keys.put("image", Collections.unmodifiableList(Arrays.asList(
                "image_url", "imgurl", "img", "image", "pic", "picture", "url", "src", "link", "href")));
        URL_KEYS_BY_KIND = Collections.unmodifiableMap(keys);

        POSTER_KEYS = Collections.unmodifiableList(Arrays.asList(
                "cover", "cover_url", "thumb", "thumbnail", "poster", "first_frame", "origin_cover",
                "image", "img", "pic"));

        TITLE_KEYS = Collections.unmodifiableList(Arrays.asList(
                "title", "name", "desc", "description", "caption", "text", "author"));
    }

    private static Map<String, Object> mode(String id, String name) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", id);
        item.put("name", name);
        return item;
    }

    /** 扩展名是否属于媒体（不含点）。 */
    public static boolean isMediaExt(String ext) {
        return ext != null && MEDIA_EXTS.contains(ext);
    }

    /** 按扩展名取 MIME，取不到给兜底值。 */
    public static String mimeOfExt(String ext, String fallback) {
        if (ext == null) return fallback;
        String key = ext.startsWith(".") ? ext.substring(1) : ext;
        String mime = MIME_BY_EXT.get(key.toLowerCase());
        return mime == null ? fallback : mime;
    }
}

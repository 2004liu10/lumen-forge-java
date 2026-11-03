package com.lumenforge.service.material;

import com.lumenforge.common.util.Texts;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图源搜索与排行榜（对应 Python {@code app/materials.py} 的
 * {@code search_images / random_images / fetch_ranking}）。
 *
 * <p>四个图源都是公开的 JSON / HTML 接口，请求头只带 UA 与可选的 Accept-Language，
 * 与 Python 版一致；结果统一成
 * {@code {id, url, thumb, width, height, title, tags, referer}}，
 * 前端只认这一套字段，所以任何图源都要补齐。
 */
@Service
public class MaterialImageSearch {

    private static final Pattern BING_M = Pattern.compile("m=\"([^\"]{0,4000})\"");

    private static final Pattern ENTITY_HEX = Pattern.compile("&#x([0-9a-f]+);", Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTITY_DEC = Pattern.compile("&#(\\d+);");

    /**
     * 搜索图片。
     *
     * @param q      关键词，空则报错（前端据此提示用户先输入）
     * @param source so360 / pixiv / booru / bing，其它值按 pixiv 处理
     */
    public Map<String, Object> search(String q, String source, int page, int limit) {
        String keyword = Texts.trim(q);
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("先输入要搜的关键词");
        }
        String src = Texts.trim(source).isEmpty() ? "pixiv" : Texts.trim(source).toLowerCase();
        int size = Math.max(1, Math.min(MaterialConstants.SEARCH_LIMIT_MAX, limit <= 0 ? 24 : limit));
        int offset = Math.max(0, page);
        List<Map<String, Object>> rows;
        if ("booru".equals(src)) {
            rows = searchBooru(keyword, size, offset);
        } else if ("bing".equals(src)) {
            rows = searchBing(keyword, size, offset);
        } else if ("so360".equals(src)) {
            rows = searchSo360(keyword, size, offset);
        } else {
            rows = searchPixiv(keyword, size);
        }
        List<Map<String, Object>> clean = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            if (isHttpUrl(Texts.str(row, "url")) && isHttpUrl(Texts.str(row, "thumb"))) {
                clean.add(row);
            }
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("没搜到 —— " + tipsFor(src));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("source", src);
        out.put("q", keyword);
        out.put("items", clean);
        return out;
    }

    /** 随机一批图：复用 pixiv 图源，空关键词即随机。 */
    public Map<String, Object> random(int page, int limit) {
        int size = Math.max(1, Math.min(20, limit <= 0 ? 16 : limit));
        List<Map<String, Object>> rows = searchPixiv("", size);
        List<Map<String, Object>> clean = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            if (isHttpUrl(Texts.str(row, "url")) && isHttpUrl(Texts.str(row, "thumb"))) {
                clean.add(row);
            }
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("随机图暂时没取到，点「换一批」再试试");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", clean);
        return out;
    }

    /**
     * 排行榜。
     *
     * <p>站点按天出榜，当天没出就用前几天的，所以要先探日期再取数据。
     */
    public Map<String, Object> ranking(String mode, int page, int limit) {
        String rankMode = Texts.trim(mode).isEmpty() ? "day" : Texts.trim(mode).toLowerCase();
        boolean known = false;
        for (Map<String, Object> item : MaterialConstants.IMAGE_RANK_MODES) {
            if (rankMode.equals(Texts.str(item, "id"))) {
                known = true;
                break;
            }
        }
        if (!known) rankMode = "day";
        int size = Math.max(1, Math.min(MaterialConstants.RANK_LIMIT_MAX, limit <= 0 ? 24 : limit));
        int pageNo = Math.max(1, page + 1);
        String date = resolveRankDate(rankMode);
        List<Map<String, Object>> rows = rankRequest(rankMode, date, pageNo, size);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", rankMode);
        out.put("date", date);
        out.put("items", rows);
        return out;
    }

    // ------------------------------------------------------------------ 图源

    private List<Map<String, Object>> searchPixiv(String keyword, int limit) {
        String url = "https://api.lolicon.app/setu/v2?num=" + limit
                + "&r18=0&size=regular&proxy=i.pixiv.re&keyword=" + encode(keyword);
        MaterialHttp.Response res = fetch(url, "");
        if (res.status >= 400) {
            throw new IllegalArgumentException("Pixiv 图源返回 HTTP " + res.status);
        }
        Map<String, Object> payload = MaterialJson.parseObject(res.text());
        if (payload == null) payload = new LinkedHashMap<String, Object>();
        String error = Texts.str(payload, "error");
        if (!error.isEmpty()) {
            throw new IllegalArgumentException("Pixiv 图源报错：" + error);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(payload.get("data"))) {
            Map<String, Object> data = asMap(raw);
            if (data == null) continue;
            Map<String, Object> urls = asMap(data.get("urls"));
            if (urls == null) urls = new LinkedHashMap<String, Object>();
            String original = Texts.str(urls, "original");
            String regular = Texts.str(urls, "regular");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", "pixiv:" + Texts.str(data, "pid") + ":" + Texts.str(data, "p"));
            row.put("url", original.isEmpty() ? regular : original);
            row.put("thumb", regular.isEmpty() ? original : regular);
            row.put("width", Texts.asInt(data.get("width"), 0));
            row.put("height", Texts.asInt(data.get("height"), 0));
            row.put("title", Texts.str(data, "title"));
            row.put("author", Texts.str(data, "author"));
            row.put("tags", tagsOf(data.get("tags"), 8));
            row.put("referer", "https://www.pixiv.net/");
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> searchBooru(String keyword, int limit, int page) {
        // safebooru 只认 URL 里的 + 当 tag 分隔符，所以必须字面拼接，不能编成 %2B
        StringBuilder tags = new StringBuilder();
        for (String part : keyword.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (tags.length() > 0) tags.append('+');
            tags.append(encode(part));
        }
        String url = "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1"
                + "&limit=" + limit + "&pid=" + page + "&tags=" + tags;
        MaterialHttp.Response res = fetch(url, "");
        if (res.status >= 400) {
            throw new IllegalArgumentException("动漫图站返回 HTTP " + res.status);
        }
        List<Object> listing = MaterialJson.parseArray(res.text());
        if (listing == null) {
            throw new IllegalArgumentException("动漫图站返回的不是 JSON，可能被拦了，稍后再试");
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : listing) {
            Map<String, Object> post = asMap(raw);
            if (post == null) continue;
            String fileUrl = Texts.str(post, "file_url");
            String sampleUrl = Texts.str(post, "sample_url");
            String previewUrl = Texts.str(post, "preview_url");
            List<String> tagWords = splitWords(Texts.str(post, "tags"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", "booru:" + Texts.str(post, "id"));
            row.put("url", fileUrl.isEmpty() ? sampleUrl : fileUrl);
            row.put("thumb", sampleUrl.isEmpty()
                    ? (previewUrl.isEmpty() ? fileUrl : previewUrl) : sampleUrl);
            row.put("width", Texts.asInt(post.get("width"), 0));
            row.put("height", Texts.asInt(post.get("height"), 0));
            row.put("title", joinFirst(tagWords, 6));
            row.put("tags", tagWords.size() > 8 ? new ArrayList<String>(tagWords.subList(0, 8)) : tagWords);
            row.put("referer", "https://safebooru.org/");
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> searchBing(String keyword, int limit, int page) {
        String url = "https://www.bing.com/images/search?q=" + encode(keyword)
                + "&form=HDRSC2&first=" + (page * limit + 1);
        MaterialHttp.Response res = fetch(url, "zh-CN,zh;q=0.9");
        if (res.status >= 400) {
            throw new IllegalArgumentException("必应返回 HTTP " + res.status);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Matcher matcher = BING_M.matcher(res.text());
        while (matcher.find() && out.size() < limit) {
            String raw = matcher.group(1);
            if (!raw.contains("murl")) continue;
            Map<String, Object> meta = MaterialJson.parseObject(decodeHtmlEntities(raw));
            if (meta == null) continue;
            String murl = Texts.str(meta, "murl");
            if (!isHttpUrl(murl)) continue;
            String turl = Texts.str(meta, "turl");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", "bing:" + murl);
            row.put("url", murl);
            row.put("thumb", isHttpUrl(turl) ? turl : murl);
            row.put("width", 0);
            row.put("height", 0);
            row.put("title", Texts.str(meta, "t"));
            row.put("tags", new ArrayList<String>());
            row.put("referer", "https://www.bing.com/");
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> searchSo360(String keyword, int limit, int page) {
        String url = "https://image.so.com/j?q=" + encode(keyword)
                + "&sn=" + (page * limit) + "&pn=" + limit + "&src=srp";
        MaterialHttp.Response res = fetch(url, "");
        if (res.status >= 400) {
            throw new IllegalArgumentException("360 图片返回 HTTP " + res.status);
        }
        Map<String, Object> payload = MaterialJson.parseObject(res.text());
        if (payload == null) {
            throw new IllegalArgumentException("360 图片返回的不是 JSON，可能被拦了，稍后再试");
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(payload.get("list"))) {
            if (out.size() >= limit) break;
            Map<String, Object> item = asMap(raw);
            if (item == null) continue;
            String img = Texts.str(item, "img");
            if (!isHttpUrl(img)) continue;
            String id = Texts.str(item, "id");
            String thumb = Texts.str(item, "thumb");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", "so360:" + (id.isEmpty() ? img : id));
            row.put("url", img);
            row.put("thumb", isHttpUrl(thumb) ? thumb : img);
            row.put("width", Texts.asInt(item.get("width"), 0));
            row.put("height", Texts.asInt(item.get("height"), 0));
            row.put("title", Texts.str(item, "title"));
            row.put("tags", new ArrayList<String>());
            row.put("referer", "");
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ 排行榜

    /** 排行榜接口固定按 UTC 日期出榜，所以这里也用 UTC 算日期。 */
    private List<String> rankDates() {
        List<String> out = new ArrayList<String>();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 1; i <= 4; i++) {
            out.add(today.minusDays(i).format(fmt));
        }
        return out;
    }

    /** 逐日探测，取第一个有数据的日期；全都没有就报站点可能在维护。 */
    private String resolveRankDate(String mode) {
        for (String date : rankDates()) {
            List<Map<String, Object>> probe = rankRequest(mode, date, 1, 5);
            if (!probe.isEmpty()) return date;
        }
        throw new IllegalArgumentException("排行榜暂时取不到数据（站点可能在维护），稍后再试");
    }

    private List<Map<String, Object>> rankRequest(String mode, String date, int page, int size) {
        String url = MaterialConstants.PIXIVIC_RANK_API
                + "?page=" + page + "&pageSize=" + size + "&date=" + date + "&mode=" + encode(mode);
        MaterialHttp.Response res = fetch(url, "");
        if (res.status >= 400) {
            throw new IllegalArgumentException("排行榜接口返回 HTTP " + res.status);
        }
        Map<String, Object> payload = MaterialJson.parseObject(res.text());
        if (payload == null) {
            throw new IllegalArgumentException("排行榜接口返回的不是 JSON，可能被拦了，稍后再试");
        }
        String error = Texts.str(payload, "error");
        if (!error.isEmpty()) {
            throw new IllegalArgumentException("排行榜接口报错：" + error);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(payload.get("data"))) {
            Map<String, Object> row = mapRankItem(asMap(raw));
            if (row != null) out.add(row);
        }
        return out;
    }

    /** 只收全年龄作品（xrestrict > 0 一律丢掉），与 Python 版的过滤一致。 */
    private Map<String, Object> mapRankItem(Map<String, Object> item) {
        if (item == null) return null;
        if ("ad_image".equals(Texts.str(item, "type"))) return null;
        List<Object> images = listOf(item.get("imageUrls"));
        Map<String, Object> first = images.isEmpty() ? null : asMap(images.get(0));
        if (first == null) first = new LinkedHashMap<String, Object>();
        String url = reachable(firstUrl(first));
        String thumb = reachable(firstThumb(first));
        if (!isHttpUrl(url) || !isHttpUrl(thumb)) return null;
        int xrestrict = Texts.asInt(item.get("xrestrict"), 0);
        if (xrestrict > 0) return null;
        List<String> tags = new ArrayList<String>();
        for (Object raw : listOf(item.get("tags"))) {
            Map<String, Object> tag = asMap(raw);
            if (tag == null) continue;
            String name = Texts.str(tag, "translatedName");
            if (name.isEmpty()) name = Texts.str(tag, "name");
            if (!name.isEmpty()) tags.add(name);
        }
        Map<String, Object> artist = asMap(item.get("artistPreView"));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", "pixivic:" + Texts.str(item, "id"));
        row.put("url", url);
        row.put("thumb", thumb);
        row.put("width", Texts.asInt(item.get("width"), 0));
        row.put("height", Texts.asInt(item.get("height"), 0));
        row.put("title", Texts.str(item, "title"));
        row.put("author", artist == null ? "" : Texts.str(artist, "name"));
        row.put("tags", tags.size() > 8 ? new ArrayList<String>(tags.subList(0, 8)) : tags);
        row.put("referer", "");
        return row;
    }

    private String firstUrl(Map<String, Object> image) {
        String original = Texts.str(image, "original");
        if (!original.isEmpty()) return original;
        String large = Texts.str(image, "large");
        if (!large.isEmpty()) return large;
        return Texts.str(image, "medium");
    }

    private String firstThumb(Map<String, Object> image) {
        String medium = Texts.str(image, "medium");
        if (!medium.isEmpty()) return medium;
        String large = Texts.str(image, "large");
        if (!large.isEmpty()) return large;
        String square = Texts.str(image, "squareMedium");
        if (!square.isEmpty()) return square;
        return Texts.str(image, "original");
    }

    /** i.pximg.net 有防盗链，换成公共反代域名才能在前端直接显示。 */
    private String reachable(String url) {
        String value = Texts.trim(url);
        if (value.toLowerCase().startsWith("http://i.pximg.net/")
                || value.toLowerCase().startsWith("https://i.pximg.net/")) {
            return "https://i.pixiv.re/" + value.substring(value.indexOf("i.pximg.net/") + "i.pximg.net/".length());
        }
        return value;
    }

    // ------------------------------------------------------------------ 工具

    private MaterialHttp.Response fetch(String url, String acceptLanguage) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", MaterialConstants.USER_AGENT);
        if (acceptLanguage != null && !acceptLanguage.isEmpty()) {
            headers.put("Accept-Language", acceptLanguage);
        }
        return MaterialHttp.get(url, headers, MaterialConstants.FETCH_TIMEOUT_MS, "请求图源失败");
    }

    private String tipsFor(String source) {
        if ("so360".equals(source)) {
            return "换个更具体的角色名试试，如「美杜莎 斗破苍穹」「云曦 完美世界」「雷电将军 原神」";
        }
        if ("booru".equals(source)) {
            return "这个源只认英文 tag，试试 1girl white_hair 这种写法；中文关键词切「360 图片」或「Pixiv」";
        }
        if ("bing".equals(source)) {
            return "换个关键词再试";
        }
        return "Pixiv 是按标签找图的，换成更短的标签再试（如「少女」「初音未来」）；描述性的话切「360 图片」或「必应图片」";
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException err) {
            return "";
        }
    }

    /** 还原 HTML 实体：必应把 JSON 塞在 HTML 属性里，不还原就解析不出来。 */
    private String decodeHtmlEntities(String text) {
        String out = text;
        Matcher hex = ENTITY_HEX.matcher(out);
        StringBuffer buffer = new StringBuffer();
        while (hex.find()) {
            String code = hex.group(1);
            try {
                hex.appendReplacement(buffer, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(code, 16))));
            } catch (NumberFormatException err) {
                hex.appendReplacement(buffer, Matcher.quoteReplacement(hex.group()));
            }
        }
        hex.appendTail(buffer);
        out = buffer.toString();
        Matcher dec = ENTITY_DEC.matcher(out);
        buffer = new StringBuffer();
        while (dec.find()) {
            try {
                dec.appendReplacement(buffer, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(dec.group(1)))));
            } catch (NumberFormatException err) {
                dec.appendReplacement(buffer, Matcher.quoteReplacement(dec.group()));
            }
        }
        dec.appendTail(buffer);
        return buffer.toString()
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private boolean isHttpUrl(String url) {
        String value = Texts.trim(url);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    @SuppressWarnings("unchecked")
    private List<Object> listOf(Object raw) {
        if (raw instanceof List) return (List<Object>) raw;
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        return null;
    }

    private List<String> tagsOf(Object raw, int max) {
        List<String> out = new ArrayList<String>();
        for (Object item : listOf(raw)) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) out.add(text);
            if (out.size() >= max) break;
        }
        return out;
    }

    private List<String> splitWords(String text) {
        List<String> out = new ArrayList<String>();
        for (String part : Texts.trim(text).split(" ")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private String joinFirst(List<String> words, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size() && i < max; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(words.get(i));
        }
        return out.toString();
    }
}

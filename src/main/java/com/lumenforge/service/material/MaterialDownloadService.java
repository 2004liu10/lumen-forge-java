package com.lumenforge.service.material;

import com.lumenforge.common.util.Texts;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 素材下载落盘。
 *
 * <p>对应 Python 版 {@code app/materials.py} 的 {@code download_material()} /
 * {@code import_local_material()} / {@code _merge_hls()} / 快照表。
 *
 * <p>三类来源：
 * <ol>
 *   <li>普通直链：原样保存（接口本来就给无水印原片，这里不做任何处理）；</li>
 *   <li>m3u8：拉播放列表 → 逐片下载 → 依序合并，直下只能拿到几 KB 文本；</li>
 *   <li>本机快照地址 {@code /api/materials/rand/<token>}：直接从内存取，不绕 HTTP 一圈。</li>
 * </ol>
 *
 * <p>文件名是 {@code md5(地址)}（{@code unique} 时再掺一个时间戳），同一个地址重复下载
 * 直接复用已有文件，不重复占磁盘。
 */
@Service
public class MaterialDownloadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialDownloadService.class);

    /** 响应类型是 m3u8：有些地址不带 .m3u8 后缀，只能靠 content-type 认出来。 */
    private static final Pattern M3U8_MIME = Pattern.compile(
            "(application/(vnd\\.apple\\.mpegurl|x-mpegurl)|audio/mpegurl)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VARIANT_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MAP_URI = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern FRAGMENTED_TAIL = Pattern.compile("\\.(m4s|mp4)(\\?|$)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXT_OK = Pattern.compile("^[a-z0-9]{2,5}$");

    /** 允许「本地导入」的临时目录名（桌面壳下载抖音素材时先落在这里）。 */
    private static final String TEMP_DIR_NAME = "douyin-tmp";

    private static final String LOCAL_SNAPSHOT_PREFIX = "/api/materials/rand/";

    private final StoragePaths paths;

    /** token → 一份二进制快照；按插入顺序淘汰，等价于 Python 的 dict + prune。 */
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<String, Snapshot>();

    public MaterialDownloadService(StoragePaths paths) {
        this.paths = paths;
    }

    // ------------------------------------------------------------------ 快照

    /** 「200 直出」接口落下的那一份内容。 */
    private static final class Snapshot {
        final byte[] buffer;
        final String mime;
        final long at;

        Snapshot(byte[] buffer, String mime, long at) {
            this.buffer = buffer;
            this.mime = mime;
            this.at = at;
        }
    }

    /**
     * 取回一份快照；过期或不存在都返回 {@code null}。
     *
     * @return {@code {buffer, mime}}，前端拿不到就提示用户刷新
     */
    public Map<String, Object> readSnapshot(String token) {
        String key = Texts.trim(token);
        if (key.isEmpty()) {
            return null;
        }
        synchronized (snapshots) {
            Snapshot snap = snapshots.get(key);
            if (snap == null) {
                return null;
            }
            if (System.currentTimeMillis() - snap.at > MaterialConstants.SNAPSHOT_TTL_MS) {
                snapshots.remove(key);
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("buffer", snap.buffer);
            out.put("mime", snap.mime);
            return out;
        }
    }

    /** 登记一份快照，返回 {@code /api/materials/rand/<token>} 形式的本地地址。 */
    private String putSnapshot(byte[] buffer, String mime) {
        String token = md5Hex(buffer).substring(0, 24);
        synchronized (snapshots) {
            snapshots.put(token, new Snapshot(buffer, mime, System.currentTimeMillis()));
            pruneSnapshots();
        }
        return LOCAL_SNAPSHOT_PREFIX + token;
    }

    /** 过期清理 + 超量淘汰，与 Python {@code _prune_snapshots()} 一致。 */
    private void pruneSnapshots() {
        long now = System.currentTimeMillis();
        Iterator<String> iterator = snapshots.keySet().iterator();
        while (iterator.hasNext()) {
            Snapshot snap = snapshots.get(iterator.next());
            if (snap == null || now - snap.at > MaterialConstants.SNAPSHOT_TTL_MS) {
                iterator.remove();
            }
        }
        while (snapshots.size() > MaterialConstants.SNAPSHOT_MAX) {
            Iterator<String> oldest = snapshots.keySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            snapshots.remove(oldest.next());
        }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 把远端素材下载到本地 {@code materials} 目录。
     *
     * @param kind    {@code image} 或 {@code video}，只在地址没扩展名时用来兜底
     * @param unique  给「每次请求都是另一段随机短片」的线路用 —— 文件名带时间戳，
     *                否则同一线路下载多次会互相覆盖，只剩最后一段
     * @return {@code {fileName, localPath, localUrl, mimeType, size, ext}}
     */
    public Map<String, Object> download(String rawUrl, String kind, String referer,
                                        Map<String, String> headers, boolean unique) {
        String raw = Texts.trim(rawUrl);
        Map<String, String> requestHeaders = new LinkedHashMap<String, String>();
        requestHeaders.put("User-Agent", MaterialConstants.USER_AGENT);
        if (headers != null) {
            requestHeaders.putAll(headers);
        }
        if (!Texts.trim(referer).isEmpty()) {
            requestHeaders.put("Referer", Texts.trim(referer));
        }

        // 「200 直出」的素材地址是本机快照，直接取内存里的那份，不必绕一圈 HTTP
        Snapshot local = localSnapshot(raw);
        if (local != null) {
            String ext = extOf(raw);
            if (ext.isEmpty()) {
                ext = MaterialConstants.MIME_TO_EXT.containsKey(local.mime)
                        ? MaterialConstants.MIME_TO_EXT.get(local.mime)
                        : defaultExt(kind);
            }
            String mime = local.mime.isEmpty()
                    ? MaterialConstants.mimeOfExt(ext, "application/octet-stream") : local.mime;
            return writeBuffer(fileNameOf(raw, ext, unique), local.buffer, mime);
        }

        String url = raw;
        if (!isHttpUrl(url)) {
            throw new IllegalArgumentException("素材地址不是合法的 http(s) 链接");
        }

        String seed = unique ? url + "#" + base36(System.currentTimeMillis()) : url;
        String fileHash = md5Hex(seed.getBytes(StandardCharsets.UTF_8));

        // HLS：直下 m3u8 只会拿到几 KB 文本，必须按分片合并成可播放的文件
        if ("m3u8".equals(extOf(url))) {
            String playlist = MaterialHttp.fetchText(url, requestHeaders, MaterialConstants.FETCH_TIMEOUT_MS,
                    "拉取播放列表失败");
            return writeHls(url, requestHeaders, playlist, fileHash);
        }

        if (!unique) {
            String existed = findDownloaded(fileHash);
            if (!existed.isEmpty()) {
                return describe(existed);
            }
        }

        Fetch fetched;
        try {
            fetched = fetch(url, requestHeaders, MaterialConstants.DOWNLOAD_TIMEOUT_MS);
        } catch (java.net.SocketTimeoutException err) {
            throw new IllegalArgumentException("下载失败：超时（60 秒没下完）");
        } catch (IOException err) {
            throw new IllegalArgumentException("下载失败：" + describe(err));
        }
        if (fetched.status >= 400) {
            throw new IllegalArgumentException("下载失败：远端返回 HTTP " + fetched.status);
        }

        String mimeType = Texts.trim(fetched.contentType).toLowerCase(Locale.ROOT);
        int semicolon = mimeType.indexOf(';');
        if (semicolon >= 0) {
            mimeType = mimeType.substring(0, semicolon).trim();
        }

        // 地址里没 .m3u8 后缀、但返回的就是播放列表（很多中转接口长这样）：也得走合并
        if (M3U8_MIME.matcher(mimeType).find()) {
            String finalUrl = fetched.finalUrl.isEmpty() ? url : fetched.finalUrl;
            return writeHls(finalUrl, requestHeaders, new String(fetched.buffer, StandardCharsets.UTF_8), fileHash);
        }

        byte[] buffer = fetched.buffer;
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("下载失败：拿到的文件是空的");
        }

        // 地址里没扩展名时（很多 CDN 是纯 ID 路径）用响应类型兜底
        String ext = extOf(url);
        if (ext.isEmpty()) {
            ext = MaterialConstants.MIME_TO_EXT.containsKey(mimeType)
                    ? MaterialConstants.MIME_TO_EXT.get(mimeType) : defaultExt(kind);
        }
        String fileName = fileHash + "." + ext;
        String mime = mimeType.isEmpty()
                ? MaterialConstants.mimeOfExt(ext, "application/octet-stream") : mimeType;
        return writeBuffer(fileName, buffer, mime);
    }

    /**
     * 登记一个「桌面壳已经下好的本地文件」，并收进持久的 {@code materials} 目录。
     *
     * <p>抖音那条链路必须带登录态下载，登录态只有桌面壳拿得到，所以文件先在临时目录落盘。
     * 临时目录会被清理，素材记录不能直接指向它，必须收进正式副本 —— 按内容 md5 命名，
     * 同一个文件重复登记不会产生第二份。
     */
    public Map<String, Object> importLocal(String rawPath, String kind) {
        Path tempRoot = paths.root().resolve(TEMP_DIR_NAME).toAbsolutePath().normalize();
        Path materialsDir = paths.materials().toAbsolutePath().normalize();
        Path target = java.nio.file.Paths.get(Texts.trim(rawPath)).toAbsolutePath().normalize();
        boolean insideTemp = target.startsWith(tempRoot);
        boolean insideMaterials = target.startsWith(materialsDir);
        if (!insideTemp && !insideMaterials) {
            throw new IllegalArgumentException("本地文件不在允许的临时目录里，已拒绝");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("下载失败：本地文件不存在");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException err) {
            throw new IllegalArgumentException("下载失败：" + describe(err));
        }
        if (size == 0) {
            throw new IllegalArgumentException("下载失败：拿到的文件是空的");
        }
        String ext = FileStorageServiceExt.extensionOf(target.getFileName().toString());
        if (ext.isEmpty()) {
            ext = defaultExt(kind);
        }
        String fileName = md5Hex(target) + "." + ext;
        Path destination = materialsDir.resolve(fileName).normalize();
        if (insideMaterials) {
            // 已经在正式目录里了，不用再复制一份
            return describe(destination.getFileName().toString());
        }
        try {
            paths.ensure(materialsDir);
            if (!Files.isRegularFile(destination)) {
                Files.copy(target, destination);
            }
        } catch (IOException err) {
            throw new IllegalStateException("保存素材失败：" + describe(err));
        }
        return describe(fileName);
    }

    // ------------------------------------------------------------------ HLS

    /** 把分片合并结果写成文件（m3u8 下载的落盘尾巴）。 */
    private Map<String, Object> writeHls(String playlistUrl, Map<String, String> headers,
                                         String playlistText, String fileHash) {
        Merged merged = mergeHls(playlistUrl, headers, playlistText);
        String fileName = fileHash + "." + merged.ext;
        String mime = MaterialConstants.mimeOfExt(merged.ext, "video/mp4");
        return writeBuffer(fileName, merged.buffer, mime);
    }

    private static final class Merged {
        final byte[] buffer;
        final String ext;

        Merged(byte[] buffer, String ext) {
            this.buffer = buffer;
            this.ext = ext;
        }
    }

    /** 逐片下载再按顺序合并。分片是 fMP4 就存 .mp4，否则存 .ts。 */
    private Merged mergeHls(String playlistUrl, Map<String, String> headers, String playlistText) {
        Playlist listing = parsePlaylist(playlistText, playlistUrl);
        if (listing.segments.isEmpty() && !listing.variants.isEmpty()) {
            Map<String, Object> best = listing.variants.get(0);
            for (Map<String, Object> candidate : listing.variants) {
                if (Texts.asInt(candidate.get("bandwidth"), 0)
                        > Texts.asInt(best.get("bandwidth"), 0)) {
                    best = candidate;
                }
            }
            String bestUrl = Texts.str(best, "url");
            listing = parsePlaylist(
                    MaterialHttp.fetchText(bestUrl, headers, MaterialConstants.FETCH_TIMEOUT_MS, "拉取播放列表失败"),
                    bestUrl);
        }
        if (listing.segments.isEmpty()) {
            throw new IllegalArgumentException("播放列表里没有分片（可能是直播流，或地址已失效）");
        }
        if (listing.segments.size() > MaterialConstants.HLS_MAX_SEGMENTS) {
            throw new IllegalArgumentException("分片太多（" + listing.segments.size()
                    + " 个），不像短视频，放弃下载");
        }

        List<String> urls = new ArrayList<String>();
        if (!listing.initSegment.isEmpty()) {
            urls.add(listing.initSegment);
        }
        urls.addAll(listing.segments);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long startedAt = System.currentTimeMillis();
        for (String segment : urls) {
            if (System.currentTimeMillis() - startedAt > MaterialConstants.HLS_BUDGET_MS) {
                throw new IllegalArgumentException("合并下载超时（分片太多或网速太慢），换个线路再试");
            }
            Fetch fetched;
            try {
                fetched = fetch(segment, headers, MaterialConstants.SEGMENT_TIMEOUT_MS);
            } catch (java.net.SocketTimeoutException err) {
                throw new IllegalArgumentException("下载分片失败：超时");
            } catch (IOException err) {
                throw new IllegalArgumentException("下载分片失败：" + describe(err));
            }
            if (fetched.status >= 400) {
                throw new IllegalArgumentException("下载分片失败：HTTP " + fetched.status);
            }
            byte[] chunk = fetched.buffer == null ? new byte[0] : fetched.buffer;
            try {
                buffer.write(chunk);
            } catch (IOException err) {
                throw new IllegalStateException("合并分片失败：" + describe(err));
            }
        }
        byte[] merged = buffer.toByteArray();
        if (merged.length == 0) {
            throw new IllegalArgumentException("合并出来的文件是空的");
        }
        boolean fragmented = !listing.initSegment.isEmpty()
                || (!urls.isEmpty() && FRAGMENTED_TAIL.matcher(urls.get(urls.size() - 1)).find());
        return new Merged(merged, fragmented ? "mp4" : "ts");
    }

    /** m3u8 结构：master 列表挑一路变体，media 列表拿分片清单。 */
    private static final class Playlist {
        final List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        final List<String> segments = new ArrayList<String>();
        String initSegment = "";
    }

    private Playlist parsePlaylist(String text, String baseUrl) {
        String body = text == null ? "" : text;
        if (!body.contains("#EXTM3U")) {
            throw new IllegalArgumentException("拿到的不是 m3u8 播放列表（缺少 #EXTM3U）");
        }
        if (Pattern.compile("#EXT-X-KEY:[^\\n]*METHOD=(?!NONE)", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
            throw new IllegalArgumentException("这个 HLS 流带加密（#EXT-X-KEY），没法直接合并成文件");
        }
        String[] rawLines = body.split("\\r?\\n", -1);
        List<String> lines = new ArrayList<String>();
        for (String line : rawLines) {
            lines.add(line.trim());
        }
        Playlist listing = new Playlist();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                index++;
                continue;
            }
            if (line.toUpperCase(Locale.ROOT).startsWith("#EXT-X-MAP:")) {
                Matcher matcher = MAP_URI.matcher(line);
                if (matcher.find()) {
                    listing.initSegment = resolveUri(matcher.group(1), baseUrl);
                }
                index++;
                continue;
            }
            if (line.toUpperCase(Locale.ROOT).startsWith("#EXT-X-STREAM-INF:")) {
                int next = index + 1;
                while (next < lines.size() && (lines.get(next).isEmpty() || lines.get(next).startsWith("#"))) {
                    next++;
                }
                if (next < lines.size()) {
                    Matcher bandwidth = VARIANT_BANDWIDTH.matcher(line);
                    Map<String, Object> variant = new LinkedHashMap<String, Object>();
                    variant.put("url", resolveUri(lines.get(next), baseUrl));
                    variant.put("bandwidth", bandwidth.find() ? Integer.parseInt(bandwidth.group(1)) : 0);
                    if (!Texts.str(variant, "url").isEmpty()) {
                        listing.variants.add(variant);
                    }
                    index = next;
                }
                index++;
                continue;
            }
            if (line.startsWith("#")) {
                index++;
                continue;
            }
            String resolved = resolveUri(line, baseUrl);
            if (!resolved.isEmpty()) {
                listing.segments.add(resolved);
            }
            index++;
        }
        return listing;
    }

    /** 播放列表里的相对地址（分片、变体、init）都按播放列表自身地址解析。 */
    private String resolveUri(String uri, String baseUrl) {
        String raw = Texts.trim(uri);
        if (raw.isEmpty()) {
            return "";
        }
        try {
            URI base = new URI(baseUrl);
            return base.resolve(raw).toString();
        } catch (Exception err) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 落盘

    /** 写一份素材文件到 {@code materials} 目录，返回给前端用的描述。 */
    private Map<String, Object> writeBuffer(String fileName, byte[] buffer, String mime) {
        Path directory = paths.materials();
        Path target = directory.resolve(fileName).normalize();
        try {
            paths.ensure(directory);
            Files.write(target, buffer);
        } catch (IOException err) {
            throw new IllegalStateException("保存素材失败：" + describe(err));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("fileName", fileName);
        out.put("localPath", target.toString());
        out.put("localUrl", "/files/materials/" + fileName);
        out.put("mimeType", mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        out.put("size", (long) buffer.length);
        out.put("ext", extOf(fileName));
        return out;
    }

    /** 已经落盘的素材描述（大小取磁盘实际值）。 */
    private Map<String, Object> describe(String fileName) {
        Path local = paths.materials().resolve(fileName).normalize();
        String ext = extOf(fileName);
        long size = 0L;
        try {
            if (Files.isRegularFile(local)) {
                size = Files.size(local);
            }
        } catch (IOException err) {
            LOGGER.warn("[material] 读取素材大小失败 {}：{}", local, err.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("fileName", fileName);
        out.put("localPath", local.toString());
        out.put("localUrl", "/files/materials/" + fileName);
        out.put("mimeType", MaterialConstants.mimeOfExt(ext, "application/octet-stream"));
        out.put("size", size);
        out.put("ext", ext);
        return out;
    }

    /** 同一个地址已经下过就直接复用，不重复占磁盘（文件名 = md5(地址)）。 */
    private String findDownloaded(String fileHash) {
        Path directory = paths.materials();
        if (!Files.isDirectory(directory)) {
            return "";
        }
        String prefix = fileHash + ".";
        try {
            java.util.stream.Stream<Path> stream = Files.list(directory);
            try {
                Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    String name = iterator.next().getFileName().toString();
                    if (name.startsWith(prefix)) {
                        return name;
                    }
                }
            } finally {
                stream.close();
            }
        } catch (IOException err) {
            return "";
        }
        return "";
    }

    // ------------------------------------------------------------------ 网络

    private static final class Fetch {
        final int status;
        final byte[] buffer;
        final String contentType;
        final String finalUrl;

        Fetch(int status, byte[] buffer, String contentType, String finalUrl) {
            this.status = status;
            this.buffer = buffer;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
        }
    }

    private Fetch fetch(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", MaterialConstants.USER_AGENT);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            int status = connection.getResponseCode();
            byte[] buffer = new byte[0];
            if (status < 400) {
                InputStream input = connection.getInputStream();
                try {
                    buffer = readAll(input);
                } finally {
                    closeQuietly(input);
                }
            }
            return new Fetch(status, buffer, connection.getContentType(),
                    connection.getURL() == null ? url : connection.getURL().toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // 关不掉不影响已经读到的内容
        }
    }

    /** 把底层异常翻成用户能看懂的一句话。 */
    private String describe(Exception err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof UnknownHostException) {
                return "连不上这个地址（域名解析失败）";
            }
            if (cause instanceof ConnectException) {
                return "连不上这个地址（连接被拒绝）";
            }
            cause = cause.getCause();
        }
        return err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
    }

    // ------------------------------------------------------------------ 工具

    private Snapshot localSnapshot(String raw) {
        if (!raw.startsWith(LOCAL_SNAPSHOT_PREFIX)) {
            return null;
        }
        String token = raw.substring(LOCAL_SNAPSHOT_PREFIX.length());
        Map<String, Object> found = readSnapshot(token);
        if (found == null) {
            throw new IllegalArgumentException("这一份随机素材已过期，点「刷新」重新拉一批");
        }
        return new Snapshot((byte[]) found.get("buffer"), Texts.str(found, "mime"), System.currentTimeMillis());
    }

    private String fileNameOf(String rawUrl, String ext, boolean unique) {
        String seed = unique ? rawUrl + "#" + base36(System.currentTimeMillis()) : rawUrl;
        return md5Hex(seed.getBytes(StandardCharsets.UTF_8)) + "." + ext;
    }

    private String defaultExt(String kind) {
        return "image".equals(kind) ? "jpg" : "mp4";
    }

    private boolean isHttpUrl(String value) {
        String text = Texts.trim(value);
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            return false;
        }
        return !text.matches("^https?://\\s+$") && !text.contains(" ");
    }

    /** 从 URL 路径里取扩展名（去掉 query），取不到返回空串。 */
    private String extOf(String url) {
        String raw = Texts.trim(url);
        if (raw.isEmpty()) {
            return "";
        }
        String path;
        try {
            path = new URI(raw).getPath();
        } catch (Exception err) {
            int cut = raw.indexOf('?');
            path = cut < 0 ? raw : raw.substring(0, cut);
        }
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXT_OK.matcher(ext).matches() ? ext : "";
    }

    private String base36(long value) {
        String digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        if (value <= 0) {
            return "0";
        }
        long remaining = value;
        StringBuilder out = new StringBuilder();
        while (remaining > 0) {
            int rem = (int) (remaining % 36);
            out.insert(0, digits.charAt(rem));
            remaining = remaining / 36;
        }
        return out.toString();
    }

    private String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    out.append('0');
                }
                out.append(hex);
            }
            return out.toString();
        } catch (Exception err) {
            throw new IllegalStateException("无法计算素材指纹：" + err.getMessage(), err);
        }
    }

    private String md5Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            InputStream input = Files.newInputStream(file);
            try {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    digest.update(chunk, 0, read);
                }
            } finally {
                closeQuietly(input);
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    out.append('0');
                }
                out.append(hex);
            }
            return out.toString();
        } catch (IOException err) {
            throw new IllegalArgumentException("下载失败：" + describe(err));
        } catch (Exception err) {
            throw new IllegalStateException("无法计算素材指纹：" + err.getMessage(), err);
        }
    }

    /**
     * 扩展名与 MIME 的兜底规则。
     *
     * <p>这里只用 {@code FileStorageService} 的静态方法，避免为了一个扩展名去注入整条存储链。
     */
    private static final class FileStorageServiceExt {
        private FileStorageServiceExt() {
        }

        /** 与 {@code FileStorageService.extensionOf()} 一致：小写、不含点、2~6 个字母数字。 */
        static String extensionOf(String name) {
            if (name == null) {
                return "";
            }
            String clean = name.trim();
            int dot = clean.lastIndexOf('.');
            if (dot < 0 || dot == clean.length() - 1) {
                return "";
            }
            String ext = clean.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.length() < 2 || ext.length() > 6) {
                return "";
            }
            for (int i = 0; i < ext.length(); i++) {
                if (!Character.isLetterOrDigit(ext.charAt(i))) {
                    return "";
                }
            }
            return ext;
        }
    }

    /** 供上层判断「这个地址是不是本机快照」。 */
    public static boolean isLocalSnapshot(String url) {
        return Texts.trim(url).startsWith(LOCAL_SNAPSHOT_PREFIX);
    }
}

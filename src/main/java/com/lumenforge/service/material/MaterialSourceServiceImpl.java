package com.lumenforge.service.material;

import com.lumenforge.common.util.Ids;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.material.MaterialItem;
import com.lumenforge.entity.material.MaterialSource;
import com.lumenforge.mapper.material.MaterialItemMapper;
import com.lumenforge.mapper.material.MaterialSourceMapper;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 素材分类（接口管理）的业务实现。
 *
 * <p>对应 Python 版 {@code app/materials.py} 的分类部分与 {@code app/api/materials_api.py}
 * 的 {@code apply_material_source_body()}。
 *
 * <p>存储方式：{@code material_sources} 表只强类型化 {@code name / source_type / enabled}，
 * 其余抓取参数全部收在 {@code config_json}（与 Python 的扁平字典一一对应）。
 * 全局线路池没有对应的表，落成数据根目录下的 {@code material-line-pool.json}，
 * 与 {@code StoragePaths} 自己存 {@code storage-paths.json} 的做法一致。
 */
@Service
public class MaterialSourceServiceImpl implements MaterialSourceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialSourceServiceImpl.class);

    /** 编号（要拼进地址）允许的字符集。 */
    private static final Pattern CLIP_ID = Pattern.compile(MaterialConstants.CLIP_ID_PATTERN);

    private static final String POOL_FILE = "material-line-pool.json";

    private final MaterialSourceMapper sources;
    private final MaterialItemMapper items;
    private final StoragePaths paths;
    private final MaterialFetcher fetcher;

    public MaterialSourceServiceImpl(MaterialSourceMapper sources,
                                     MaterialItemMapper items,
                                     StoragePaths paths,
                                     MaterialFetcher fetcher) {
        this.sources = sources;
        this.items = items;
        this.paths = paths;
        this.fetcher = fetcher;
    }

    // ------------------------------------------------------------------ 分类

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MaterialSource source : sources.findAllByOrderByCreatedAtAsc()) {
            out.add(snapshot(source));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String name = Texts.str(body, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写分类名称");
        }
        Map<String, Object> config = defaults();
        config.put("name", name);
        applyBody(config, body);
        MaterialSource source = new MaterialSource(
                Ids.nextId("ms"),
                name,
                kindOf(config),
                Texts.asBool(config, "enabled", true),
                MaterialJson.write(config, "{}"));
        MaterialSource saved = sources.save(source);
        return snapshot(saved);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        MaterialSource source = require(id);
        Map<String, Object> config = configOf(source);
        config.put("name", source.getName());
        applyBody(config, body);
        String name = Texts.str(config, "name");
        source.setName(name.isEmpty() ? source.getName() : name);
        source.setSourceType(kindOf(config));
        source.setEnabled(Texts.asBool(config, "enabled", source.isEnabled()));
        source.setConfigJson(MaterialJson.write(config, "{}"));
        source.touch();
        MaterialSource saved = sources.save(source);
        invalidate(source.getId());
        return snapshot(saved);
    }

    @Override
    @Transactional
    public void delete(String id) {
        MaterialSource source = require(id);
        invalidate(source.getId());
        sources.delete(source);
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialSource require(String id) {
        MaterialSource source = id == null || id.trim().isEmpty()
                ? null
                : sources.findById(id.trim()).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("素材分类不存在");
        }
        return source;
    }

    @Override
    public Map<String, Object> snapshot(MaterialSource source) {
        Map<String, Object> config = configOf(source);
        List<String> own = ownLines(config);
        String mode = Texts.str(config, "mode");
        List<String> built = new ArrayList<String>();
        if ("m3u8".equals(mode)) {
            for (String line : materialLines(source)) {
                if (!own.contains(line)) built.add(line);
            }
        }
        String apiKey = Texts.str(config, "apiKey");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", source.getId());
        out.put("name", source.getName());
        out.put("kind", kindOf(config));
        out.put("mode", mode.isEmpty() ? "api" : mode);
        out.put("lines", own);
        out.put("clip", Texts.str(config, "clip"));
        out.put("count", Texts.asInt(config, "count", 8));
        out.put("headers", Texts.str(config, "headers"));
        out.put("apiKey", mask(apiKey));
        out.put("apiKeyHeader", headerName(config));
        out.put("apiKeyPrefix", Texts.str(config, "apiKeyPrefix"));
        out.put("itemsPath", Texts.str(config, "itemsPath"));
        out.put("urlField", Texts.str(config, "urlField"));
        out.put("extraParams", Texts.str(config, "extraParams"));
        out.put("enabled", source.isEnabled());
        out.put("createdAt", epochMs(source.getCreatedAt()));
        String lastGood = Texts.str(config, "lastGoodLine");
        if (!lastGood.isEmpty()) {
            out.put("lastGoodLine", lastGood);
        }
        out.put("builtLines", built);
        out.put("hasKey", !apiKey.isEmpty());
        return out;
    }

    // ------------------------------------------------------------------ 线路池

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> linePool() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("lines", readPool());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> updateLinePool(Map<String, Object> body) {
        Object raw = null;
        if (body != null) {
            if (body.containsKey("__array__")) {
                raw = body.get("__array__");
            } else if (body.containsKey("lines")) {
                raw = body.get("lines");
            }
        }
        List<String> pool = normalizeLinePool(raw);
        for (String line : pool) {
            URI parsed;
            try {
                parsed = new URI(line);
            } catch (Exception err) {
                throw new IllegalArgumentException(
                        "线路基地址不合法：" + line + "（要 http(s) 开头的完整域名，不带编号）");
            }
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("线路基地址必须以 http(s) 开头：" + line);
            }
            String rawPath = parsed.getRawPath() == null ? "" : parsed.getRawPath();
            if (!rawPath.replace("/", "").trim().isEmpty()) {
                throw new IllegalArgumentException("线路基地址只填域名根，不要带路径：" + line);
            }
        }
        writePool(pool);
        invalidateAll();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("lines", pool);
        return out;
    }

    // ------------------------------------------------------------------ 取素材

    @Override
    @Transactional
    public Map<String, Object> items(String id, boolean refresh) {
        MaterialSource source = require(id);
        Map<String, Object> config = configOf(source);
        String mode = Texts.str(config, "mode");
        if ("douyin".equals(mode)) {
            throw new IllegalArgumentException(
                    "「抖音采集」不用在这里刷新：素材模块里会内嵌抖音网页，在页面里刷到想要的点「＋」加到画布");
        }
        if (!refresh) {
            List<MaterialItem> cached = items.findBySourceIdOrderByFetchedAtDesc(source.getId());
            if (!cached.isEmpty() && isFresh(cached.get(0).getFetchedAt())) {
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", itemViews(cached));
                out.put("line", Texts.str(config, "lastGoodLine"));
                out.put("fetchedAt", epochMs(cached.get(0).getFetchedAt()));
                return out;
            }
        }
        Map<String, Object> fetched = fetcher.fetch(source, headers(source), materialLines(source));
        List<Map<String, Object>> rows = castRows(fetched == null ? null : fetched.get("items"));
        List<String> lines = castStrings(fetched == null ? null : fetched.get("lines"));
        String message = fetched == null ? "" : Texts.trim(fetched.get("message"));
        replaceCache(source.getId(), rows, lines, message);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", rows);
        out.put("line", lines.isEmpty() ? "" : lines.get(0));
        out.put("fetchedAt", Ids.nowMs());
        if (!message.isEmpty()) {
            out.put("message", message);
        }
        return out;
    }

    // ------------------------------------------------------------------ 请求头 / 线路

    @Override
    public Map<String, String> headers(MaterialSource source) {
        Map<String, Object> config = configOf(source);
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("User-Agent", MaterialConstants.USER_AGENT);
        String raw = Texts.str(config, "headers");
        if (!raw.isEmpty()) {
            Map<String, Object> parsed = MaterialJson.parseObject(raw);
            if (parsed == null) {
                throw new IllegalArgumentException("请求头不是合法的 JSON：" + raw);
            }
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                out.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        String apiKey = Texts.str(config, "apiKey");
        if (!apiKey.isEmpty()) {
            String header = headerName(config);
            String prefix = Texts.str(config, "apiKeyPrefix");
            out.put(header, prefix + apiKey);
        }
        return out;
    }

    @Override
    public List<String> materialLines(MaterialSource source) {
        Map<String, Object> config = configOf(source);
        List<String> own = ownLines(config);
        String mode = Texts.str(config, "mode");
        if (!"m3u8".equals(mode)) {
            return own;
        }
        String clip = Texts.str(config, "clip");
        if (clip.isEmpty() || !CLIP_ID.matcher(clip).matches()) {
            return own;
        }
        List<String> built = new ArrayList<String>();
        for (String base : readPool()) {
            built.add(base + "/" + clip + ".m3u8");
        }
        List<String> out = new ArrayList<String>();
        for (String line : built) {
            if (!out.contains(line)) out.add(line);
        }
        for (String line : own) {
            if (!out.contains(line)) out.add(line);
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    /** 新分类的默认 config，字段顺序与 Python {@code new_material_source()} 一致。 */
    private Map<String, Object> defaults() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("name", "素材分类");
        config.put("kind", "video");
        config.put("mode", "api");
        config.put("lines", new ArrayList<String>());
        config.put("clip", "");
        config.put("count", 8);
        config.put("headers", "");
        config.put("apiKey", "");
        config.put("apiKeyHeader", "Authorization");
        config.put("apiKeyPrefix", "Bearer ");
        config.put("itemsPath", "");
        config.put("urlField", "");
        config.put("extraParams", "");
        config.put("enabled", true);
        return config;
    }

    /** 读 config_json，缺的键补默认值（老数据 / 手改 JSON 都不至于炸）。 */
    private Map<String, Object> configOf(MaterialSource source) {
        Map<String, Object> config = defaults();
        config.put("kind", source.getSourceType() == null || source.getSourceType().trim().isEmpty()
                ? "video" : source.getSourceType().trim());
        config.put("enabled", source.isEnabled());
        Map<String, Object> parsed = MaterialJson.parseObject(source.getConfigJson());
        if (parsed != null) {
            config.putAll(parsed);
            if (!parsed.containsKey("kind") || Texts.str(parsed, "kind").isEmpty()) {
                config.put("kind", source.getSourceType() == null || source.getSourceType().trim().isEmpty()
                        ? "video" : source.getSourceType().trim());
            }
        }
        return config;
    }

    /**
     * 把请求体套到 config 上。
     *
     * <p>逐条对应 Python {@code apply_material_source_body()}：只有请求体里出现的键才改，
     * 掩码回显的 Key 不覆盖真 Key，编号要过字符集校验。
     */
    private void applyBody(Map<String, Object> config, Map<String, Object> body) {
        if (body == null) return;
        if (body.get("name") != null) {
            String name = Texts.trim(body.get("name"));
            if (!name.isEmpty()) config.put("name", name);
        }
        if (body.containsKey("kind")) {
            config.put("kind", "image".equals(Texts.str(body, "kind")) ? "image" : "video");
        }
        // 抖音采集分类的 mode 是固定的，它的取素材逻辑完全不同于 m3u8/api/redirect
        if (!"douyin".equals(Texts.str(config, "mode")) && body.containsKey("mode")) {
            String mode = Texts.str(body, "mode");
            config.put("mode", "m3u8".equals(mode) || "redirect".equals(mode) ? mode : "api");
        }
        if (body.containsKey("lines")) {
            config.put("lines", normalizeLinePool(body.get("lines")));
        }
        if (body.containsKey("clip")) {
            String clip = Texts.trim(body.get("clip"));
            if (!clip.isEmpty() && !CLIP_ID.matcher(clip).matches()) {
                throw new IllegalArgumentException("编号只能用字母、数字和 - _ .（要拼进地址）");
            }
            config.put("clip", clip);
        }
        if (body.containsKey("count")) {
            int count = Texts.asInt(body.get("count"), 8);
            if (count == 0) count = 8;
            config.put("count", Math.max(1, Math.min(48, count)));
        }
        if (body.containsKey("headers")) {
            config.put("headers", Texts.str(body, "headers"));
        }
        // 密钥同图片模型：掩码回显不能覆盖真 Key
        String apiKey = Texts.str(body, "apiKey");
        if (!apiKey.isEmpty() && !apiKey.contains("****")) {
            config.put("apiKey", apiKey);
        }
        if (body.containsKey("apiKeyHeader")) {
            String header = Texts.trim(body.get("apiKeyHeader"));
            config.put("apiKeyHeader", header.isEmpty() ? "Authorization" : header);
        }
        if (body.containsKey("apiKeyPrefix")) {
            config.put("apiKeyPrefix", Texts.str(body, "apiKeyPrefix"));
        }
        if (body.containsKey("itemsPath")) {
            config.put("itemsPath", Texts.trim(body.get("itemsPath")));
        }
        if (body.containsKey("urlField")) {
            config.put("urlField", Texts.trim(body.get("urlField")));
        }
        if (body.containsKey("extraParams")) {
            config.put("extraParams", Texts.str(body, "extraParams"));
        }
        if (body.containsKey("enabled")) {
            config.put("enabled", Texts.asBool(body.get("enabled"), Texts.asBool(config, "enabled", true)));
        }
    }

    private String kindOf(Map<String, Object> config) {
        return "image".equals(Texts.str(config, "kind")) ? "image" : "video";
    }

    private String headerName(Map<String, Object> config) {
        String header = Texts.str(config, "apiKeyHeader");
        return header.isEmpty() ? "Authorization" : header;
    }

    /** 分类自己填的线路（不含 m3u8 拼出来的那批）。 */
    private List<String> ownLines(Map<String, Object> config) {
        return normalizeLinePool(config.get("lines"));
    }

    /**
     * 线路归一化：接受字符串数组或 {@code [{"url": "..."}]}，去尾部斜杠、去空、去重。
     *
     * <p>与 Python {@code normalize_line_pool()} 一致。
     */
    @SuppressWarnings("unchecked")
    private List<String> normalizeLinePool(Object raw) {
        List<String> out = new ArrayList<String>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<Object>) raw) {
            String value = null;
            if (item instanceof String) {
                value = (String) item;
            } else if (item instanceof Map) {
                Object url = ((Map<String, Object>) item).get("url");
                if (url != null) value = String.valueOf(url);
            }
            if (value == null) continue;
            String line = value.trim();
            while (line.endsWith("/")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isEmpty() && !out.contains(line)) out.add(line);
        }
        return out;
    }

    private Path poolFile() {
        return paths.root().resolve(POOL_FILE);
    }

    private List<String> readPool() {
        Path file = poolFile();
        if (!Files.isRegularFile(file)) return new ArrayList<String>();
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Object> parsed = MaterialJson.parseObject(text);
            if (parsed == null) return new ArrayList<String>();
            return normalizeLinePool(parsed.get("lines"));
        } catch (IOException err) {
            LOGGER.warn("[material] 读取线路池失败：{}", err.getMessage());
            return new ArrayList<String>();
        }
    }

    private void writePool(List<String> pool) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("lines", pool);
        Path file = paths.root().resolve(POOL_FILE);
        try {
            paths.ensure(file.getParent());
            Files.write(file, MaterialJson.write(payload, "{\"lines\":[]}").getBytes(StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new IllegalStateException("无法保存线路池：" + err.getMessage(), err);
        }
    }

    private boolean isFresh(LocalDateTime fetchedAt) {
        if (fetchedAt == null) return false;
        long age = Duration.between(fetchedAt, LocalDateTime.now()).toMillis();
        return age >= 0 && age < MaterialConstants.CACHE_TTL_MS;
    }

    /** LocalDateTime → 毫秒时间戳（前端统一用毫秒）。 */
    private long epochMs(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void invalidate(String sourceId) {
        try {
            items.deleteBySourceId(sourceId);
        } catch (Exception err) {
            LOGGER.warn("[material] 清理分类缓存失败：{}", err.getMessage());
        }
    }

    private void invalidateAll() {
        try {
            items.deleteAllInBatch();
        } catch (Exception err) {
            LOGGER.warn("[material] 清理全部素材缓存失败：{}", err.getMessage());
        }
    }

    /** 把抓取结果换成缓存行：先清旧、再写新，与 Python 的整批替换语义一致。 */
    private void replaceCache(String sourceId, List<Map<String, Object>> rows, List<String> lines, String message) {
        invalidate(sourceId);
        if (rows.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        List<MaterialItem> saved = new ArrayList<MaterialItem>();
        for (Map<String, Object> row : rows) {
            String url = Texts.str(row, "url");
            if (url.isEmpty()) continue;
            String itemId = Texts.str(row, "id");
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("poster", Texts.str(row, "poster"));
            meta.put("ext", Texts.str(row, "ext"));
            meta.put("hls", Texts.asBool(row.get("hls"), false));
            saved.add(new MaterialItem(
                    Ids.nextId("mi"),
                    sourceId,
                    itemId.isEmpty() ? url : itemId,
                    Texts.truncate(Texts.str(row, "title"), 500),
                    null,
                    url,
                    null,
                    "[]",
                    MaterialJson.write(meta, "{}"),
                    now));
        }
        if (!saved.isEmpty()) items.saveAll(saved);
    }

    private List<Map<String, Object>> itemViews(List<MaterialItem> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MaterialItem row : rows) {
            Map<String, Object> meta = MaterialJson.parseObject(row.getMetadataJson());
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("id", row.getSourceItemId());
            view.put("url", row.getMediaUrl());
            view.put("poster", meta == null ? "" : Texts.str(meta, "poster"));
            view.put("title", row.getTitle());
            view.put("ext", meta == null ? "" : Texts.str(meta, "ext"));
            view.put("hls", meta != null && Texts.asBool(meta.get("hls"), false));
            out.add(view);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<Object>) raw) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> castStrings(Object raw) {
        List<String> out = new ArrayList<String>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<Object>) raw) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    /** Key 掩码：与图片模型一致（长度≤8 全掩，否则前 4 + **** + 后 4）。 */
    private String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}

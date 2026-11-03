package com.lumenforge.service.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Ids;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.publish.PublishRecord;
import com.lumenforge.mapper.publish.PublishRecordMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 发布记录业务实现。
 *
 * <p>逐条对齐 {@code app/publish_log.py}：身份判定用 {@code path + size + mtimeMs} 三者全等，
 * 而不是只看路径 —— 同一个路径的视频被重新导出过（剪辑软件再出一版）时 size 与 mtime 会变，
 * 那就该当成一条新作品，不能因为「路径见过」就拦着不让发。
 *
 * <p>表 {@code publish_records} 只强类型化了 {@code title / description / status /
 * published_at / created_at}，其余字段（{@code path / size / mtimeMs / topics / filledAt /
 * fillCount / createdAt} 的毫秒原值 / {@code deletedAt}）收在 {@code metadata_json}。
 *
 * <p>跨模块的数据（视频任务成品、画布任务成品、引用计数）一律用 {@link JdbcTemplate}
 * 读原始列，不依赖其它模块的实体类 —— 这样本模块的编译不随别人的改动摇摆。
 */
@Service
@Transactional
public class PublishServiceImpl implements PublishService {

    /** 认作「视频」的扩展名，与 Python 的 {@code VIDEO_EXTS} 一致。 */
    private static final List<String> VIDEO_EXTS = Collections.unmodifiableList(
            java.util.Arrays.asList(".mp4", ".mov", ".mkv", ".webm", ".avi"));

    /** 列表最多返回的作品条数。 */
    private static final int MAX_WORKS = 200;

    /** 一条记录最多带 20 个话题。 */
    private static final int MAX_TOPICS = 20;

    /** 记录的目标平台；本模块只对接抖音发布。 */
    private static final String PLATFORM = "douyin";

    private static final String STATUS_FILLED = "filled";
    private static final String STATUS_PUBLISHED = "published";

    private final PublishRecordMapper records;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PublishServiceImpl(PublishRecordMapper records, JdbcTemplate jdbc, ObjectMapper json) {
        this.records = records;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ 记录列表

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listRecords() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listWorks() {
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        candidates.addAll(videoTaskWorks());
        candidates.addAll(canvasTaskWorks());

        // 只留文件真实还在的。任务记录会一直留着，但视频文件可能早就被删掉了 ——
        // 那种「记录还在、片子没了」的条目挑也挑不动，不该占列表。
        List<Map<String, Object>> alive = new ArrayList<Map<String, Object>>();
        int hidden = 0;
        for (Map<String, Object> work : candidates) {
            String path = Texts.trim(work.get("path"));
            if (!isRegularFile(path)) {
                hidden++;
                continue;
            }
            // 标出哪些已经发布过（用文件身份对，不是只对路径 —— 重新导出过的算新作品）
            Map<String, Object> hit = findByPathOrIdentity(path);
            work.put("published", hit != null && STATUS_PUBLISHED.equals(Texts.trim(hit.get("status"))));
            work.put("publishedAt", hit == null ? 0L : Texts.asLong(hit.get("publishedAt"), 0L));
            alive.add(work);
        }

        Collections.sort(alive, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long a = Texts.asLong(left.get("createdAt"), 0L);
                long b = Texts.asLong(right.get("createdAt"), 0L);
                if (a == b) {
                    return 0;
                }
                return a < b ? 1 : -1;
            }
        });

        List<Map<String, Object>> works = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> work : alive) {
            if (works.size() >= MAX_WORKS) {
                break;
            }
            works.add(work);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("works", works);
        // 路由层 Python 写的是 `out.get("hidden") or []`：0 会被 or 成空数组，非 0 才是数字。
        // 这里照抄该行为，保证前端拿到的形状与旧版一致。
        result.put("hidden", hidden == 0 ? new ArrayList<Object>() : (Object) hidden);
        return result;
    }

    // ------------------------------------------------------------------ 查重

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> check(Map<String, Object> body) {
        String path = stringOr(body == null ? null : body.get("path"));
        FileIdentity identity = identity(path);
        if (identity == null) {
            // 文件不在（被移走 / 已经清理掉了）。这时**不能**直接说「查不到就算了」——
            // 那等于把「这条发过」这件事忘掉，万一用户从回收站还原回来就会重发。
            Map<String, Object> byPath = findLatestByPath(path);
            if (byPath != null && STATUS_PUBLISHED.equals(Texts.trim(byPath.get("status")))) {
                return checkResult(true, false, byPath, "这条视频已经发布过了（本地文件已被移走或清理）");
            }
            if (byPath != null && STATUS_FILLED.equals(Texts.trim(byPath.get("status")))) {
                return checkResult(false, true, byPath, "这条视频填过表还没确认发布，但本地文件已经不在了");
            }
            return checkResult(false, false, null, "文件不存在");
        }

        Map<String, Object> exact = findByIdentity(identity);
        if (exact != null && STATUS_PUBLISHED.equals(Texts.trim(exact.get("status")))) {
            return checkResult(true, false, exact, "这条视频已经发布过了");
        }
        if (exact != null && STATUS_FILLED.equals(Texts.trim(exact.get("status")))) {
            return checkResult(false, true, exact, "这条视频刚填过表，可能已经在抖音页面上传好了");
        }
        // 同路径但内容变了：提一句，不当成重复
        Map<String, Object> byPath = findLatestByPath(identity.path);
        if (byPath != null) {
            return checkResult(false, true, byPath, "这个文件之前发过，但内容好像改过了");
        }
        return checkResult(false, false, null, "");
    }

    private Map<String, Object> checkResult(boolean blocked, boolean warn, Map<String, Object> record, String reason) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("blocked", blocked);
        result.put("warn", warn);
        result.put("record", record);
        result.put("reason", reason);
        return result;
    }

    // ------------------------------------------------------------------ 写操作

    @Override
    public Map<String, Object> markFilled(Map<String, Object> body) {
        String path = stringOr(body == null ? null : body.get("path"));
        Object rawName = body == null ? null : body.get("name");
        String desc = stringOr(body == null ? null : body.get("desc"));
        Object rawTopics = body == null ? null : body.get("topics");

        FileIdentity identity = identity(path);
        if (identity == null) {
            throw new IllegalArgumentException("找不到这个视频文件，可能被移动或删除了");
        }

        long now = Ids.nowMs();
        List<Object> topics = rawTopics instanceof List ? new ArrayList<Object>((List<?>) rawTopics) : new ArrayList<Object>();
        if (topics.size() > MAX_TOPICS) {
            topics = new ArrayList<Object>(topics.subList(0, MAX_TOPICS));
        }

        PublishRecord record = findByEntityIdentity(identity);
        if (record != null) {
            // 又填了一次：状态回到 filled（上一轮可能只是填了没发），并刷新文案
            record.setStatus(STATUS_PUBLISHED.equals(record.getStatus()) ? STATUS_PUBLISHED : STATUS_FILLED);
            record.setDescription(desc);
            Map<String, Object> meta = readMeta(record);
            long fillCount = Texts.asLong(meta.get("fillCount"), 1L) + 1L;
            meta.put("topics", topics);
            meta.put("filledAt", now);
            meta.put("fillCount", fillCount);
            record.setMetadataJson(writeMeta(meta));
            records.save(record);
        } else {
            String name = Texts.trim(rawName);
            if (name.isEmpty()) {
                name = fileName(identity.path);
            }
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("path", identity.path);
            meta.put("size", identity.size);
            meta.put("mtimeMs", identity.mtimeMs);
            meta.put("topics", topics);
            meta.put("filledAt", now);
            meta.put("fillCount", 1L);
            meta.put("createdAt", now);
            record = new PublishRecord(Ids.nextId("pub"), PLATFORM, name, desc,
                    STATUS_FILLED, null, writeMeta(meta), toLocalDateTime(now));
            records.save(record);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("record", view(record, false));
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    public Map<String, Object> markPublished(String id) {
        PublishRecord record = require(id);
        record.setStatus(STATUS_PUBLISHED);
        record.setPublishedAt(LocalDateTime.now());
        Map<String, Object> meta = readMeta(record);
        meta.put("publishedAt", Ids.nowMs());
        record.setMetadataJson(writeMeta(meta));
        records.save(record);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    public Map<String, Object> markPublishedByPath(Map<String, Object> body) {
        String path = stringOr(body == null ? null : body.get("path"));
        FileIdentity identity = identity(path);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        PublishRecord record = identity == null ? null : findByEntityIdentity(identity);
        if (record == null) {
            result.put("ok", true);
            result.put("record", null);
            result.put("records", recordViews(true));
            return result;
        }
        markPublished(record.getId());
        result.put("ok", true);
        result.put("record", view(require(record.getId()), false));
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    public Map<String, Object> unpublish(String id) {
        PublishRecord record = require(id);
        record.setStatus(STATUS_FILLED);
        record.setPublishedAt(null);
        Map<String, Object> meta = readMeta(record);
        meta.put("publishedAt", 0L);
        record.setMetadataJson(writeMeta(meta));
        records.save(record);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    public Map<String, Object> removeRecord(String id) {
        PublishRecord record = require(id);
        records.delete(record);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("records", recordViews(true));
        return result;
    }

    @Override
    public Map<String, Object> clearRecords() {
        long removed = records.count();
        records.deleteAllInBatch();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("removed", removed);
        result.put("records", new ArrayList<Object>());
        return result;
    }

    // ------------------------------------------------------------------ 删除分析

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> analyzeDeletable(Map<String, Object> body) {
        boolean includeFilled = Texts.asBool(body == null ? null : body.get("includeFilled"), false);
        Map<String, Integer> references = collectReferences();

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> record : recordViews(false)) {
            String status = Texts.trim(record.get("status"));
            if (!STATUS_PUBLISHED.equals(status) && !(includeFilled && STATUS_FILLED.equals(status))) {
                continue;
            }
            String path = Texts.trim(record.get("path"));
            boolean exists = isRegularFile(path);
            long size = exists ? sizeOf(path) : 0L;
            // 这里正是那个「显式清理」的入口，要是把自己也算进去，所有文件都会
            // 被判成「有人用」，永远删不掉 —— 所以只数画布素材 / 任务结果 / 参考图
            // 这些**真正的使用方**，发布记录本身排除在外。
            int refs = exists ? countOf(references, path) : 0;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", record.get("id"));
            item.put("path", path);
            item.put("name", Texts.trim(record.get("name")).isEmpty() ? fileName(path) : record.get("name"));
            item.put("size", size);
            item.put("exists", exists);
            item.put("status", status);
            item.put("publishedAt", Texts.asLong(record.get("publishedAt"), 0L));
            item.put("filledAt", Texts.asLong(record.get("filledAt"), 0L));
            item.put("refs", refs);
            item.put("usedElsewhere", refs > 0);
            items.add(item);
        }

        List<Map<String, Object>> deletable = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> inUse = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> missing = new ArrayList<Map<String, Object>>();
        long deletableBytes = 0L;
        long allBytes = 0L;
        for (Map<String, Object> item : items) {
            long size = Texts.asLong(item.get("size"), 0L);
            allBytes += size;
            boolean exists = Boolean.TRUE.equals(item.get("exists"));
            if (!exists) {
                missing.add(item);
            } else if (Boolean.TRUE.equals(item.get("usedElsewhere"))) {
                inUse.add(item);
            } else {
                deletable.add(item);
                deletableBytes += size;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("deletable", deletable);
        result.put("inUse", inUse);
        result.put("missing", missing);
        result.put("totalCount", items.size());
        result.put("deletableCount", deletable.size());
        result.put("deletableBytes", deletableBytes);
        result.put("inUseCount", inUse.size());
        result.put("missingCount", missing.size());
        result.put("allBytes", allBytes);
        return result;
    }

    @Override
    public Map<String, Object> markDeleted(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        List<String> wanted = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    wanted.add(String.valueOf(item));
                }
            }
        }
        long now = Ids.nowMs();
        int marked = 0;
        for (PublishRecord record : records.findAll()) {
            if (!wanted.contains(record.getId())) {
                continue;
            }
            Map<String, Object> meta = readMeta(record);
            meta.put("deletedAt", now);
            record.setMetadataJson(writeMeta(meta));
            records.save(record);
            marked++;
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("marked", marked);
        result.put("records", recordViews(true));
        return result;
    }

    // ------------------------------------------------------------------ 作品来源

    /** ① 视频生成模块（图生视频）的成品。 */
    private List<Map<String, Object>> videoTaskWorks() {
        List<Map<String, Object>> works = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT id, prompt, result_json, created_at, finished_at FROM video_tasks ORDER BY created_at DESC");
        } catch (Exception exception) {
            // 表还没建（例如 h2 配置下未启用 Flyway）时不要拖垮整个发布页
            return works;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> out = videoOutputOf(row.get("result_json"));
            String localPath = Texts.trim(out.get("localPath"));
            if (localPath.isEmpty()) {
                continue;
            }
            Map<String, Object> work = new LinkedHashMap<String, Object>();
            work.put("source", "video");
            work.put("taskId", row.get("id"));
            work.put("index", 0);
            work.put("path", localPath);
            work.put("name", Texts.trim(out.get("fileName")).isEmpty()
                    ? fileName(localPath) : Texts.trim(out.get("fileName")));
            work.put("size", Texts.asLong(out.get("size"), 0L));
            work.put("url", Texts.trim(out.get("localUrl")));
            work.put("origin", "视频生成");
            work.put("desc", Texts.truncate(Texts.trim(row.get("prompt")), 70));
            work.put("enhanced", hasTopaz(out));
            work.put("enhancedLabel", topazLabel(out));
            long createdAt = Texts.asLong(row.get("finished_at"), Texts.asLong(row.get("created_at"), 0L));
            work.put("createdAt", createdAt);
            works.add(work);
        }
        return works;
    }

    /** ② 自用模块（RunningHub 画布）的视频结果。 */
    private List<Map<String, Object>> canvasTaskWorks() {
        List<Map<String, Object>> works = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT id, canvas_name, outputs_json, created_at FROM generation_tasks ORDER BY created_at DESC");
        } catch (Exception exception) {
            return works;
        }
        for (Map<String, Object> row : rows) {
            List<Object> outputs = asList(readJson(row.get("outputs_json")));
            for (int index = 0; index < outputs.size(); index++) {
                Object value = outputs.get(index);
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<String, Object> out = castMap((Map<?, ?>) value);
                String localPath = Texts.trim(out.get("localPath"));
                if (localPath.isEmpty()) {
                    continue;
                }
                if (!VIDEO_EXTS.contains(extension(localPath))) {
                    continue;
                }
                Map<String, Object> work = new LinkedHashMap<String, Object>();
                work.put("source", "task");
                work.put("taskId", row.get("id"));
                work.put("index", index);
                work.put("path", localPath);
                work.put("name", fileName(localPath));
                work.put("size", Texts.asLong(out.get("size"), 0L));
                work.put("url", Texts.trim(out.get("localUrl")));
                String canvasName = Texts.trim(row.get("canvas_name"));
                work.put("origin", canvasName.isEmpty() ? "自用" : canvasName);
                work.put("desc", "");
                work.put("enhanced", hasTopaz(out));
                work.put("enhancedLabel", topazLabel(out));
                work.put("createdAt", Texts.asLong(row.get("created_at"), 0L));
                works.add(work);
            }
        }
        return works;
    }

    /** 从视频任务的 {@code result_json} 里取出视频结果对象。 */
    private Map<String, Object> videoOutputOf(Object raw) {
        Object parsed = readJson(raw);
        if (!(parsed instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> root = castMap((Map<?, ?>) parsed);
        Object nested = root.get("video");
        if (nested instanceof Map) {
            return castMap((Map<?, ?>) nested);
        }
        if (Texts.trim(root.get("localPath")).isEmpty() && root.get("result") instanceof Map) {
            return castMap((Map<?, ?>) root.get("result"));
        }
        return root;
    }

    private boolean hasTopaz(Map<String, Object> out) {
        Object topaz = out.get("topaz");
        if (!(topaz instanceof Map)) {
            return false;
        }
        return ((Map<?, ?>) topaz).get("at") != null && !"".equals(String.valueOf(((Map<?, ?>) topaz).get("at")));
    }

    private String topazLabel(Map<String, Object> out) {
        Object topaz = out.get("topaz");
        if (!(topaz instanceof Map)) {
            return "";
        }
        Object label = ((Map<?, ?>) topaz).get("tierLabel");
        return label == null ? "" : String.valueOf(label);
    }

    // ------------------------------------------------------------------ 引用计数

    /**
     * 全库「正在被引用的本地文件路径」计数（按记录计数，不是去重）。
     *
     * <p>与 Python {@code shared_files.collect_references} 对应，但这里只扫本 Java 版
     * 实际存在的表：画布素材、生成任务结果、图片任务结果、视频任务结果与输入、
     * 参考图与分镜定妆照。发布记录本身不计（调用方是「显式清理」入口）。
     */
    private Map<String, Integer> collectReferences() {
        final Map<String, Integer> counts = new HashMap<String, Integer>();

        // 1) 任何 JSON 列里出现的 localPath 都算一条引用
        for (String column : new String[]{"canvases:config_json", "generation_tasks:outputs_json",
                "image_tasks:result_json", "video_tasks:result_json", "storyboards:script_json"}) {
            String table = column.substring(0, column.indexOf(':'));
            String field = column.substring(column.indexOf(':') + 1);
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList("SELECT " + field + " FROM " + table);
            } catch (Exception exception) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                walkForLocalPath(readJson(row.get(field)), counts);
            }
        }

        // 2) 按 file_id 引用的表，需要换算成磁盘路径
        Map<String, String> storedPaths = storedFilePaths();
        for (String sql : new String[]{
                "SELECT file_id FROM image_references",
                "SELECT file_id FROM video_inputs",
                "SELECT input_id FROM video_tasks",
                "SELECT end_input_id FROM video_tasks",
                "SELECT image_file_id FROM storyboard_scenes",
                "SELECT video_file_id FROM storyboard_scenes",
                "SELECT source_file_id FROM topaz_jobs",
                "SELECT output_file_id FROM topaz_jobs"}) {
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList(sql);
            } catch (Exception exception) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                for (Object value : row.values()) {
                    if (value == null) {
                        continue;
                    }
                    String stored = storedPaths.get(String.valueOf(value));
                    if (stored != null) {
                        hit(counts, stored);
                    }
                }
            }
        }

        // 3) 分镜角色的定妆照：角色上存的是 refId，要按 id 找到那份参考图再计数
        List<Map<String, Object>> boards;
        try {
            boards = jdbc.queryForList("SELECT script_json FROM storyboards");
        } catch (Exception exception) {
            boards = new ArrayList<Map<String, Object>>();
        }
        for (Map<String, Object> board : boards) {
            for (String refId : walkForRefId(readJson(board.get("script_json")))) {
                String fileId = imageReferenceFileId(refId);
                if (fileId == null) {
                    continue;
                }
                String stored = storedPaths.get(fileId);
                if (stored != null) {
                    hit(counts, stored);
                }
            }
        }
        return counts;
    }

    private Map<String, String> storedFilePaths() {
        Map<String, String> out = new HashMap<String, String>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT id, relative_path FROM stored_files");
        } catch (Exception exception) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            String relative = Texts.trim(row.get("relative_path"));
            if (relative.isEmpty()) {
                continue;
            }
            out.put(String.valueOf(row.get("id")), normalize(relative));
        }
        return out;
    }

    private String imageReferenceFileId(String referenceId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT file_id FROM image_references WHERE id = ?", referenceId);
        } catch (Exception exception) {
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.get(0).get("file_id");
        return value == null ? null : String.valueOf(value);
    }

    private void walkForLocalPath(Object node, Map<String, Integer> counts) {
        if (node instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
                Object value = entry.getValue();
                if ("localPath".equals(String.valueOf(entry.getKey())) && value != null) {
                    hit(counts, String.valueOf(value));
                } else {
                    walkForLocalPath(value, counts);
                }
            }
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) {
                walkForLocalPath(value, counts);
            }
        }
    }

    private List<String> walkForRefId(Object node) {
        List<String> found = new ArrayList<String>();
        collectRefIds(node, found);
        return found;
    }

    private void collectRefIds(Object node, List<String> found) {
        if (node instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
                Object value = entry.getValue();
                if ("refId".equals(String.valueOf(entry.getKey())) && value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty()) {
                        found.add(text);
                    }
                } else {
                    collectRefIds(value, found);
                }
            }
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) {
                collectRefIds(value, found);
            }
        }
    }

    private void hit(Map<String, Integer> counts, String rawPath) {
        String normalized = normalize(rawPath);
        if (normalized.isEmpty()) {
            return;
        }
        Integer old = counts.get(normalized);
        counts.put(normalized, old == null ? 1 : old + 1);
    }

    private int countOf(Map<String, Integer> counts, String rawPath) {
        Integer value = counts.get(normalize(rawPath));
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ 记录视图

    /** 全部记录的视图；{@code withFileExists} 时补「文件还在吗」。 */
    private List<Map<String, Object>> recordViews(boolean withFileExists) {
        List<PublishRecord> rows = records.findAll();
        Collections.sort(rows, new Comparator<PublishRecord>() {
            @Override
            public int compare(PublishRecord left, PublishRecord right) {
                long a = sortKey(left);
                long b = sortKey(right);
                if (a == b) {
                    return 0;
                }
                return a < b ? 1 : -1;
            }
        });
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (PublishRecord row : rows) {
            views.add(view(row, withFileExists));
        }
        return views;
    }

    private Map<String, Object> view(PublishRecord record, boolean withFileExists) {
        Map<String, Object> meta = readMeta(record);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", record.getId());
        out.put("path", Texts.trim(meta.get("path")));
        out.put("size", Texts.asLong(meta.get("size"), 0L));
        out.put("mtimeMs", Texts.asLong(meta.get("mtimeMs"), 0L));
        out.put("name", record.getTitle() == null ? "" : record.getTitle());
        out.put("desc", record.getDescription() == null ? "" : record.getDescription());
        out.put("topics", asList(meta.get("topics")));
        out.put("status", record.getStatus());
        out.put("publishedAt", Texts.asLong(meta.get("publishedAt"), fromLocalDateTime(record.getPublishedAt())));
        out.put("filledAt", Texts.asLong(meta.get("filledAt"), 0L));
        out.put("fillCount", Texts.asLong(meta.get("fillCount"), 1L));
        out.put("createdAt", Texts.asLong(meta.get("createdAt"), fromLocalDateTime(record.getCreatedAt())));
        if (Texts.asLong(meta.get("deletedAt"), 0L) > 0L) {
            out.put("deletedAt", Texts.asLong(meta.get("deletedAt"), 0L));
        }
        if (withFileExists) {
            // 用 exists 而不是 isRegularFile：Python 侧这里就是 os.path.exists，
            // 目录也会算「在」。路径里有非法字符时 Paths.get 会抛，按「不在」处理即可。
            boolean exists = false;
            String rawPath = Texts.trim(meta.get("path"));
            if (!rawPath.isEmpty()) {
                try {
                    exists = Files.exists(Paths.get(rawPath));
                } catch (Exception exception) {
                    exists = false;
                }
            }
            out.put("fileExists", exists);
        }
        return out;
    }

    /** 排序键：{@code publishedAt or filledAt or createdAt}。 */
    private long sortKey(PublishRecord record) {
        Map<String, Object> meta = readMeta(record);
        long published = Texts.asLong(meta.get("publishedAt"), fromLocalDateTime(record.getPublishedAt()));
        if (published > 0L) {
            return published;
        }
        long filled = Texts.asLong(meta.get("filledAt"), 0L);
        if (filled > 0L) {
            return filled;
        }
        long created = Texts.asLong(meta.get("createdAt"), fromLocalDateTime(record.getCreatedAt()));
        return created;
    }

    // ------------------------------------------------------------------ 身份与查找

    /** 视频文件的当前身份（文件不在了就返回 {@code null}）。 */
    private FileIdentity identity(String rawPath) {
        String raw = Texts.trim(rawPath);
        if (raw.isEmpty()) {
            return null;
        }
        Path path = Paths.get(raw);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileIdentity(normalize(raw), attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (Exception exception) {
            return null;
        }
    }

    private PublishRecord findByEntityIdentity(FileIdentity identity) {
        for (PublishRecord record : records.findAll()) {
            Map<String, Object> meta = readMeta(record);
            String storedPath = normalize(Texts.trim(meta.get("path")));
            if (identity.path.equals(storedPath) && identity.size == Texts.asLong(meta.get("size"), -1L)
                    && identity.mtimeMs == Texts.asLong(meta.get("mtimeMs"), -1L)) {
                return record;
            }
        }
        return null;
    }

    private Map<String, Object> findByIdentity(FileIdentity identity) {
        PublishRecord record = findByEntityIdentity(identity);
        return record == null ? null : view(record, false);
    }

    /** 找「这条路径下最近的一条记录」，不管内容变没变。 */
    private Map<String, Object> findLatestByPath(String rawPath) {
        String target = normalize(rawPath);
        if (target.isEmpty()) {
            return null;
        }
        PublishRecord latest = null;
        long latestKey = -1L;
        for (PublishRecord record : records.findAll()) {
            String path = Texts.trim(readMeta(record).get("path"));
            if (!target.equals(normalize(path))) {
                continue;
            }
            long key = sortKey(record);
            if (key > latestKey) {
                latestKey = key;
                latest = record;
            }
        }
        return latest == null ? null : view(latest, false);
    }

    /** 先按身份、再按同路径最近一条（作品的「发过没有」就是这么判的）。 */
    private Map<String, Object> findByPathOrIdentity(String path) {
        FileIdentity identity = identity(path);
        if (identity != null) {
            Map<String, Object> exact = findByIdentity(identity);
            if (exact != null) {
                return exact;
            }
        }
        return findLatestByPath(path);
    }

    private PublishRecord require(String id) {
        return records.findById(id).orElseThrow(() -> new IllegalArgumentException("这条发布记录不存在"));
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 解析 {@code metadata_json}。MySQL 的 JSON 列读回来就是原文本，而 H2 等把字符串写进 JSON 列时会再包一层
     * 引号（{@code "{\"a\":1}"}），所以这里遇到字符串就再解一层，直到拿到对象为止。
     */
    private Map<String, Object> readMeta(PublishRecord record) {
        Object parsed = readJson(record.getMetadataJson());
        int guard = 0;
        while (parsed instanceof String && guard < 4) {
            parsed = readJson(parsed);
            guard++;
        }
        if (parsed instanceof Map) {
            return castMap((Map<?, ?>) parsed);
        }
        return new LinkedHashMap<String, Object>();
    }

    private String writeMeta(Map<String, Object> meta) {
        try {
            return json.writeValueAsString(meta);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化发布记录扩展信息", exception);
        }
    }

    private Object readJson(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<Object>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private List<Object> asList(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<?>) value);
        }
        return new ArrayList<Object>();
    }

    private Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private String stringOr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 绝对化 + 归一化，与 Python {@code Path.resolve()} 的比较语义对齐。 */
    private String normalize(String raw) {
        String text = Texts.trim(raw);
        if (text.isEmpty()) {
            return "";
        }
        try {
            return Paths.get(text).toAbsolutePath().normalize().toString();
        } catch (Exception exception) {
            return text;
        }
    }

    private String fileName(String rawPath) {
        String text = Texts.trim(rawPath);
        if (text.isEmpty()) {
            return "";
        }
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        return slash < 0 ? text : text.substring(slash + 1);
    }

    /** 小写扩展名（含点）；没有则空串。 */
    private String extension(String rawPath) {
        String name = fileName(rawPath);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private boolean isRegularFile(String rawPath) {
        String text = Texts.trim(rawPath);
        if (text.isEmpty()) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(text));
        } catch (Exception exception) {
            return false;
        }
    }

    private long sizeOf(String rawPath) {
        try {
            return Files.size(Paths.get(Texts.trim(rawPath)));
        } catch (Exception exception) {
            return 0L;
        }
    }

    private static LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private static long fromLocalDateTime(LocalDateTime value) {
        return value == null ? 0L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 视频文件身份：{@code path + size + mtimeMs} 三者一起才算「同一条片子」。 */
    private static final class FileIdentity {
        private final String path;
        private final long size;
        private final long mtimeMs;

        private FileIdentity(String path, long size, long mtimeMs) {
            this.path = path;
            this.size = size;
            this.mtimeMs = mtimeMs;
        }
    }
}

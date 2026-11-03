package com.lumenforge.service.topic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Ids;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.topic.TopicHistory;
import com.lumenforge.mapper.topic.TopicHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 话题库业务实现。
 *
 * <p>行为逐条对齐 {@code app/topics.py}：
 * <ul>
 *   <li>名字归一化：去前导 {@code #} → 去所有空白 → 截 30 字（{@link #MAX_NAME}）。</li>
 *   <li>一次最多收 12 个（{@link #MAX_PER_CALL}）。</li>
 *   <li>已存在的不重复建，只在对方 {@code sourceDesc} 为空时补一次。</li>
 *   <li>列表排序：用过的按最近使用降序排最前，没用过的按收录时间倒序。</li>
 * </ul>
 *
 * <p>表 {@code topic_history} 只强类型化了 {@code topic / category / used / used_at /
 * created_at}，其余展示字段（{@code sourceDesc / usedCount / lastUsedAt / createdAt} 的毫秒原值）
 * 收在 {@code metadata_json}，与 Python 的扁平字典一一对应。
 */
@Service
@Transactional
public class TopicServiceImpl implements TopicService {

    /** 一条话题最多 30 字，超出直接截断（抖音本身也有限制）。 */
    private static final int MAX_NAME = 30;

    /** 一次最多收 12 个。 */
    private static final int MAX_PER_CALL = 12;

    /** 来源描述最多 80 字。 */
    private static final int MAX_SOURCE_DESC = 80;

    private final TopicHistoryMapper topics;
    private final ObjectMapper json;

    public TopicServiceImpl(TopicHistoryMapper topics, ObjectMapper json) {
        this.topics = topics;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> list(String q) {
        List<TopicRow> all = loadRows();
        String keyword = normalize(q);
        List<TopicRow> rows = all;
        if (!keyword.isEmpty()) {
            List<TopicRow> filtered = new ArrayList<TopicRow>();
            for (TopicRow row : all) {
                if (row.name.contains(keyword)) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }
        Collections.sort(rows, ORDER);
        // 注意：stats 统计的是**整库**，不是当前搜索命中的子集 ——
        // Python 的 topic_stats() 直接读全量库，搜索时界面上的「共 N 个」不能跟着变。
        return bundle(rows, all);
    }

    @Override
    public Map<String, Object> add(Map<String, Object> body) {
        Object names = body == null ? null : body.get("names");
        if (names == null && body != null) {
            names = body.get("name");
        }
        String source = stringOr(body == null ? null : body.get("source"));
        if (source.isEmpty()) {
            source = "douyin";
        }
        String sourceDesc = stringOr(body == null ? null : body.get("sourceDesc"));

        List<String> wanted = wantedNames(names);
        List<String> added = new ArrayList<String>();
        List<String> existed = new ArrayList<String>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();

        for (String name : wanted) {
            TopicRow hit = findByName(name);
            if (hit != null) {
                // 已经有的：补一下来源描述（留着最近一次的出处，方便回想）
                if (!sourceDesc.isEmpty() && hit.sourceDesc.isEmpty()) {
                    hit.entity.setMetadataJson(writeMetadata(truncate(sourceDesc, MAX_SOURCE_DESC),
                            hit.usedCount, hit.lastUsedAt, hit.createdAt, hit.meta));
                    topics.save(hit.entity);
                    hit = new TopicRow(hit.entity);
                }
                existed.add(name);
                items.add(view(hit));
                continue;
            }
            TopicHistory created = createTopic(name, source, sourceDesc);
            topics.save(created);
            added.add(name);
            items.add(view(new TopicRow(created)));
        }

        Map<String, Object> result = bundle(loadRows(), null);
        result.put("added", added);
        result.put("existed", existed);
        result.put("items", items);
        return result;
    }

    @Override
    public Map<String, Object> markUsed(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("names");
        // 与 Python 一致：只认数组，传字符串等于什么都没做
        List<String> wanted = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                String name = normalize(item);
                if (!name.isEmpty()) {
                    wanted.add(name);
                }
            }
        }
        int touched = 0;
        long now = Ids.nowMs();
        for (String name : wanted) {
            TopicRow hit = findByName(name);
            if (hit == null) {
                continue;
            }
            hit.entity.setUsed(true);
            hit.entity.setUsedAt(toLocalDateTime(now));
            hit.entity.setMetadataJson(writeMetadata(hit.sourceDesc, hit.usedCount + 1, now, hit.createdAt, hit.meta));
            topics.save(hit.entity);
            touched++;
        }
        Map<String, Object> result = bundle(loadRows(), null);
        result.put("touched", touched);
        return result;
    }

    @Override
    public Map<String, Object> delete(String id) {
        TopicHistory entity = topics.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("这个话题不在库里"));
        topics.delete(entity);
        Map<String, Object> result = bundle(loadRows(), null);
        result.put("ok", true);
        return result;
    }

    @Override
    public Map<String, Object> clear() {
        long removed = topics.count();
        topics.deleteAllInBatch();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("removed", removed);
        return result;
    }

    // ------------------------------------------------------------------ 内部

    /** 列表响应：{@code {topics, stats}}，额外的字段由调用方塞进去。 */
    private Map<String, Object> bundle(List<TopicRow> rows, List<TopicRow> statsSource) {
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (TopicRow row : rows) {
            views.add(view(row));
        }
        int used = 0;
        int total = 0;
        for (TopicRow row : (statsSource == null ? rows : statsSource)) {
            total++;
            if (row.usedCount > 0) {
                used++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("topics", views);
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("total", total);
        stats.put("used", used);
        result.put("stats", stats);
        return result;
    }

    private Map<String, Object> view(TopicRow row) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", row.entity.getId());
        result.put("name", row.name);
        result.put("source", row.entity.getCategory() == null ? "" : row.entity.getCategory());
        result.put("sourceDesc", row.sourceDesc);
        result.put("usedCount", (int) row.usedCount);
        result.put("lastUsedAt", row.lastUsedAt);
        result.put("createdAt", row.createdAt);
        return result;
    }

    private List<TopicRow> loadRows() {
        List<TopicRow> rows = new ArrayList<TopicRow>();
        for (TopicHistory entity : topics.findAll()) {
            rows.add(new TopicRow(entity));
        }
        return rows;
    }

    /** 按名字找第一条（Python {@code store.find} 的插入顺序语义）。 */
    private TopicRow findByName(String name) {
        for (TopicRow row : loadRows()) {
            if (row.name.equals(name)) {
                return row;
            }
        }
        return null;
    }

    private TopicHistory createTopic(String name, String source, String sourceDesc) {
        long now = Ids.nowMs();
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("sourceDesc", truncate(sourceDesc, MAX_SOURCE_DESC));
        meta.put("usedCount", 0);
        meta.put("lastUsedAt", 0L);
        meta.put("createdAt", now);
        return new TopicHistory(Ids.nextId("tp"), name, source.trim(),
                false, writeMetadataJson(meta), toLocalDateTime(now), null);
    }

    /** 把一批原始名字归一化、去空、截到 12 个。 */
    private List<String> wantedNames(Object names) {
        List<Object> raw = new ArrayList<Object>();
        if (names instanceof List) {
            raw.addAll((List<?>) names);
        } else if (names != null) {
            raw.add(names);
        }
        List<String> wanted = new ArrayList<String>();
        for (Object item : raw) {
            String name = normalize(item);
            if (!name.isEmpty()) {
                wanted.add(name);
            }
            if (wanted.size() >= MAX_PER_CALL) {
                break;
            }
        }
        return wanted;
    }

    /** 去前导 # → 去所有空白 → 截 30 字。 */
    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        int start = 0;
        while (start < text.length() && text.charAt(start) == '#') {
            start++;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                out.append(c);
            }
        }
        return Texts.truncate(out.toString(), MAX_NAME);
    }

    /** Python {@code str_or(value)}：{@code None → ""}，其它一律转字符串。 */
    private String stringOr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        return Texts.truncate(value == null ? "" : value, max);
    }

    private String writeMetadata(String sourceDesc, long usedCount, long lastUsedAt, long createdAt,
                                 Map<String, Object> old) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        if (old != null) {
            meta.putAll(old);
        }
        meta.put("sourceDesc", sourceDesc == null ? "" : sourceDesc);
        meta.put("usedCount", usedCount);
        meta.put("lastUsedAt", lastUsedAt);
        meta.put("createdAt", createdAt);
        return writeMetadataJson(meta);
    }

    private String writeMetadataJson(Map<String, Object> meta) {
        try {
            return json.writeValueAsString(meta);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化话题扩展信息", exception);
        }
    }

    /**
     * 解析 {@code metadata_json}。MySQL 的 JSON 列读回来就是原文本，而 H2 等把字符串写进 JSON 列时会再包一层
     * 引号（{@code "{\"a\":1}"}），所以这里遇到字符串就再解一层，直到拿到对象为止。
     */
    private Map<String, Object> readMetadata(String raw) {
        Object parsed = decodeJson(raw);
        int guard = 0;
        while (parsed instanceof String && guard < 4) {
            parsed = decodeJson((String) parsed);
            guard++;
        }
        if (parsed instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Object decodeJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<Object>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private static long fromLocalDateTime(LocalDateTime value) {
        return value == null ? 0L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 排序：用过的（按最近使用降序）永远排在没用过的（按收录时间倒序）前面。 */
    private static final Comparator<TopicRow> ORDER = new Comparator<TopicRow>() {
        @Override
        public int compare(TopicRow left, TopicRow right) {
            boolean leftUsed = left.usedCount > 0;
            boolean rightUsed = right.usedCount > 0;
            if (leftUsed != rightUsed) {
                return leftUsed ? -1 : 1;
            }
            if (leftUsed) {
                return compareLong(right.lastUsedAt, left.lastUsedAt);
            }
            return compareLong(right.createdAt, left.createdAt);
        }
    };

    private static int compareLong(long left, long right) {
        if (left == right) {
            return 0;
        }
        return left < right ? -1 : 1;
    }

    /** 一条话题：实体 + 从 {@code metadata_json} 解出来的展示字段。 */
    private final class TopicRow {
        private final TopicHistory entity;
        private final Map<String, Object> meta;
        private final String name;
        private final String sourceDesc;
        private final long usedCount;
        private final long lastUsedAt;
        private final long createdAt;

        private TopicRow(TopicHistory entity) {
            this.entity = entity;
            this.meta = readMetadata(entity.getMetadataJson());
            this.name = entity.getTopic() == null ? "" : entity.getTopic();
            this.sourceDesc = stringOr(meta.get("sourceDesc"));
            this.usedCount = Texts.asLong(meta.get("usedCount"), 0L);
            this.lastUsedAt = Texts.asLong(meta.get("lastUsedAt"), 0L);
            this.createdAt = Texts.asLong(meta.get("createdAt"), fromLocalDateTime(entity.getCreatedAt()));
        }
    }
}

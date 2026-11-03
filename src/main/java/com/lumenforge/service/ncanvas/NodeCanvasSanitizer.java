package com.lumenforge.service.ncanvas;

import com.lumenforge.common.util.Ids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 节点画布数据的清洗规则。
 *
 * <p>逐函数复刻 Python 版 {@code app/node_canvas.py} 的 {@code _text / _num / sanitize_gen /
 * sanitize_agent / sanitize_node / sanitize_graph / sanitize_chat / chat_title_of / chat_meta}。
 * 所有上限常量、白名单、扩展名表都与原版一字不差 —— 少存一个字段，重开画布就丢一项能力。
 */
public final class NodeCanvasSanitizer {

    /** 一张图最多这么多节点 / 连线：防止误导入一个巨型 JSON 把界面拖死。 */
    public static final int MAX_NODES = 400;
    public static final int MAX_EDGES = 800;

    /** 节点类型白名单：前端只认这几种，导入外部 JSON 时也必须过这一关。 */
    public static final Set<String> NODE_TYPES = unmodifiableSet(
            "text", "image", "video", "audio", "imageGen", "videoGen");

    /** AI 编导铺出来的节点带一份角色标记，「一键生成」按它分波次。 */
    public static final Set<String> AGENT_ROLES = unmodifiableSet("cast", "scene", "shot", "video");

    /** 连线可选色（和 public/nodecanvas.css 里 .nc-edge[data-color="…"] 一一对应）。 */
    public static final Set<String> EDGE_COLORS = unmodifiableSet("teal", "violet", "gold", "rose", "slate");

    /** 视频节点入边的角色（首帧 / 尾帧 / 普通参考图）。 */
    public static final Set<String> EDGE_ROLES = unmodifiableSet("start", "end", "ref");

    /** 聊天记录上限。 */
    public static final int MAX_CHAT_MESSAGES = 120;
    public static final int MAX_CHAT_TEXT = 8000;
    public static final int MAX_CHAT_PHOTOS = 4;
    /** 一张画布的对话总量上限：别让一段闲聊把库撑爆。 */
    public static final int MAX_CHAT_TOTAL = 60000;
    /** 一张画布最多留几段历史对话。 */
    public static final int MAX_CHATS = 8;
    public static final int MAX_CHAT_TITLE = 32;

    /** 媒体大类 → 允许的扩展名（含点、小写）。 */
    public static final Map<String, List<String>> KIND_EXTS;

    /** 媒体大类 → 默认 MIME。 */
    public static final Map<String, String> KIND_MIME;

    static {
        Map<String, List<String>> exts = new LinkedHashMap<String, List<String>>();
        exts.put("image", Arrays.asList(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".avif"));
        exts.put("video", Arrays.asList(".mp4", ".webm", ".mov", ".mkv", ".m4v", ".ogv"));
        exts.put("audio", Arrays.asList(".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac"));
        KIND_EXTS = java.util.Collections.unmodifiableMap(exts);

        Map<String, String> mime = new LinkedHashMap<String, String>();
        mime.put("image", "image/png");
        mime.put("video", "video/mp4");
        mime.put("audio", "audio/mpeg");
        KIND_MIME = java.util.Collections.unmodifiableMap(mime);
    }

    private NodeCanvasSanitizer() {
    }

    private static Set<String> unmodifiableSet(String... values) {
        Set<String> out = new LinkedHashSet<String>();
        for (String value : values) {
            out.add(value);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    // ============================================================ 小工具

    /**
     * {@code text(value, max)} —— 转字符串并按字符（不是 UTF-16 单元）截断。
     *
     * <p>Python 的 {@code str(v)[:max]} 按码点切，直接用 {@code substring} 会把 emoji 的代理对
     * 从中间劈开，序列化时就变成半个字符。
     */
    public static String text(Object value, int maxLength) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value);
        if (raw.length() <= maxLength) {
            return raw;
        }
        int end = raw.offsetByCodePoints(0, Math.min(maxLength, raw.codePointCount(0, raw.length())));
        return raw.substring(0, end);
    }

    public static String text(Object value) {
        return text(value, 4000);
    }

    /**
     * {@code num(value, fallback, min, max)} —— 非有限数回落，其余夹在区间内。
     *
     * <p>注意 Python 的 {@code float(True)} 等于 1.0，布尔值也要按数字认。
     */
    public static double num(Object value, double fallback, double low, double high) {
        double parsed;
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            parsed = ((Boolean) value) ? 1.0 : 0.0;
        } else if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else {
            String raw = String.valueOf(value).trim();
            if (raw.isEmpty()) {
                return fallback;
            }
            try {
                parsed = Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return fallback;
        }
        return Math.min(high, Math.max(low, parsed));
    }

    /**
     * Python {@code round(x)} 的等价：银行家舍入（半值取偶）。
     *
     * <p>{@code Math.round(2.5)} 是 3，而 Python 的 {@code round(2.5)} 是 2 —— 用 {@code Math.rint}
     * 才对得上，否则导出的数据会出现 ±1 的漂移。
     */
    public static long roundInt(double value) {
        return (long) Math.rint(value);
    }

    /** Python 的真值判断（空串 / 0 / 空容器为假）。 */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof java.util.Collection) {
            return !((java.util.Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    /** 按扩展名（含点、小写）判断媒体大类，认不出返回空串。 */
    public static String extKind(String ext) {
        if (ext == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> entry : KIND_EXTS.entrySet()) {
            if (entry.getValue().contains(ext)) {
                return entry.getKey();
            }
        }
        return "";
    }

    /** 小写扩展名（含点）；没有则返回空串。 */
    public static String suffix(String name) {
        String clean = name == null ? "" : name;
        int dot = clean.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return clean.substring(dot).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object raw) {
        return raw instanceof List ? (List<Object>) raw : null;
    }

    // ============================================================ 清洗导入数据

    /** {@code gen} 字段白名单。 */
    public static Map<String, Object> sanitizeGen(Object raw) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> source = asMap(raw);
        if (source == null) {
            return out;
        }
        out.put("modelId", text(source.get("modelId"), 80));
        out.put("prompt", text(source.get("prompt"), 4000));
        // 重新生成时填的「这次想改什么」：和 prompt 分开存
        out.put("revise", text(source.get("revise"), 500));
        out.put("size", text(source.get("size"), 40));
        out.put("count", roundInt(num(source.get("count"), 1, 1, 4)));
        out.put("status", text(source.get("status"), 20));
        out.put("progress", text(source.get("progress"), 200));
        out.put("taskId", text(source.get("taskId"), 80));
        out.put("resultUrl", text(source.get("resultUrl"), 2000));
        out.put("error", text(source.get("error"), 500));
        out.put("duration", text(source.get("duration"), 20));
        out.put("resolution", text(source.get("resolution"), 40));
        out.put("ratio", text(source.get("ratio"), 20));
        out.put("seed", text(source.get("seed"), 40));
        // 豆包内部模型档：不保留就会「每存一次盘被打回跟随当前档」
        out.put("doubaoTier", text(source.get("doubaoTier"), 20));
        return out;
    }

    /** AI 编导留在节点上的标记；不是对象或角色不在白名单时返回 {@code null}。 */
    public static Map<String, Object> sanitizeAgent(Object raw) {
        Map<String, Object> source = asMap(raw);
        if (source == null) {
            return null;
        }
        String role = source.get("role") == null ? "" : String.valueOf(source.get("role"));
        if (!AGENT_ROLES.contains(role)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("role", role);
        out.put("planId", text(source.get("planId"), 40));
        out.put("planTitle", text(source.get("planTitle"), 120));
        out.put("shotIndex", roundInt(num(source.get("shotIndex"), 0, 0, 999)));
        out.put("character", text(source.get("character"), 40));
        out.put("motion", text(source.get("motion"), 1000));
        out.put("dialogue", text(source.get("dialogue"), 200));
        out.put("scene", text(source.get("scene"), 600));
        out.put("stylePrompt", text(source.get("stylePrompt"), 300));
        out.put("castDesc", text(source.get("castDesc"), 600));
        out.put("shotSize", text(source.get("shotSize"), 12));
        out.put("angle", text(source.get("angle"), 12));
        out.put("light", text(source.get("light"), 60));
        out.put("desc", text(source.get("desc"), 400));
        out.put("frame", text(source.get("frame"), 120));
        out.put("endState", text(source.get("endState"), 400));
        out.put("place", text(source.get("place"), 40));
        out.put("toPlace", text(source.get("toPlace"), 40));
        out.put("setting", text(source.get("setting"), 300));
        out.put("fromShot", roundInt(num(source.get("fromShot"), 0, 0, 999)));
        out.put("toShot", roundInt(num(source.get("toShot"), 0, 0, 999)));
        out.put("refKeep", truthy(source.get("refKeep")));
        out.put("fromPhoto", truthy(source.get("fromPhoto")));
        return out;
    }

    /** 单个节点；类型不在白名单时返回 {@code null}。 */
    public static Map<String, Object> sanitizeNode(Object raw, Set<String> usedIds) {
        Map<String, Object> source = asMap(raw);
        if (source == null) {
            return null;
        }
        String nodeType = source.get("type") == null ? "" : String.valueOf(source.get("type"));
        if (!NODE_TYPES.contains(nodeType)) {
            return null;
        }

        String nodeId = text(source.get("id"), 64);
        if (nodeId.isEmpty() || usedIds.contains(nodeId)) {
            nodeId = Ids.nextId("nd");
        }
        usedIds.add(nodeId);

        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", nodeId);
        node.put("type", nodeType);
        node.put("x", num(source.get("x"), 80, -1e6, 1e6));
        node.put("y", num(source.get("y"), 80, -1e6, 1e6));
        node.put("w", num(source.get("w"), 260, 140, 1600));
        node.put("h", num(source.get("h"), 180, 90, 1600));
        node.put("title", text(source.get("title"), 120));
        node.put("text", text(source.get("text"), 8000));
        node.put("url", text(source.get("url"), 2000));
        node.put("localUrl", text(source.get("localUrl"), 2000));
        node.put("localPath", text(source.get("localPath"), 1000));
        node.put("fileName", text(source.get("fileName"), 300));
        node.put("assetId", text(source.get("assetId"), 80));
        // 媒体分辨率是前端量好后存在节点上的（nc-media-dim）
        node.put("mediaW", roundInt(num(source.get("mediaW"), 0, 0, 100000)));
        node.put("mediaH", roundInt(num(source.get("mediaH"), 0, 0, 100000)));
        node.put("manualSize", truthy(source.get("manualSize")));
        node.put("autoSized", truthy(source.get("autoSized")));
        node.put("gen", sanitizeGen(source.get("gen")));
        Map<String, Object> agent = sanitizeAgent(source.get("agent"));
        if (agent != null) {
            node.put("agent", agent);
        }
        return node;
    }

    /** 整张图的清洗：节点 → 连线 → 视图。 */
    public static Map<String, Object> sanitizeGraph(Object raw) {
        Map<String, Object> source = asMap(raw);
        if (source == null) {
            source = new LinkedHashMap<String, Object>();
        }

        List<Object> listedNodes = asList(source.get("nodes"));
        Set<String> usedIds = new HashSet<String>();
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        if (listedNodes != null) {
            for (Object item : listedNodes) {
                Map<String, Object> node = sanitizeNode(item, usedIds);
                if (node != null) {
                    nodes.add(node);
                }
                if (nodes.size() >= MAX_NODES) {
                    break;
                }
            }
        }

        Set<String> ids = new HashSet<String>();
        for (Map<String, Object> node : nodes) {
            ids.add((String) node.get("id"));
        }

        List<Object> listedEdges = asList(source.get("edges"));
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        Set<String> usedEdges = new HashSet<String>();
        if (listedEdges != null) {
            for (Object item : listedEdges) {
                Map<String, Object> edgeRaw = asMap(item);
                if (edgeRaw == null) {
                    continue;
                }
                String src = edgeRaw.get("from") == null ? "" : String.valueOf(edgeRaw.get("from"));
                String dst = edgeRaw.get("to") == null ? "" : String.valueOf(edgeRaw.get("to"));
                if (!ids.contains(src) || !ids.contains(dst) || src.equals(dst)) {
                    continue;
                }
                String edgeId = text(edgeRaw.get("id"), 64);
                if (edgeId.isEmpty() || usedEdges.contains(edgeId)) {
                    edgeId = Ids.nextId("eg");
                }
                usedEdges.add(edgeId);
                Map<String, Object> edge = new LinkedHashMap<String, Object>();
                edge.put("id", edgeId);
                edge.put("from", src);
                edge.put("to", dst);
                // 连线颜色不保留的话，保存一次就被洗回默认灰
                String color = text(edgeRaw.get("color"), 24);
                if (EDGE_COLORS.contains(color)) {
                    edge.put("color", color);
                }
                // 连线角色（首帧 / 尾帧 / 普通参考图）：猜反了就是「首帧当尾帧」
                String role = text(edgeRaw.get("role"), 12);
                if (EDGE_ROLES.contains(role)) {
                    edge.put("role", role);
                }
                edges.add(edge);
                if (edges.size() >= MAX_EDGES) {
                    break;
                }
            }
        }

        Map<String, Object> viewport = asMap(source.get("viewport"));
        if (viewport == null) {
            viewport = new LinkedHashMap<String, Object>();
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("x", num(viewport.get("x"), 80, -1e6, 1e6));
        view.put("y", num(viewport.get("y"), 80, -1e6, 1e6));
        view.put("zoom", num(viewport.get("zoom"), 1, 0.2, 3));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("viewport", view);
        return out;
    }

    // ============================================================ 聊天记录

    /** 单条聊天消息；角色不在白名单或正文与图片都为空时返回 {@code null}。 */
    public static Map<String, Object> sanitizeChatMessage(Object raw) {
        Map<String, Object> source = asMap(raw);
        if (source == null) {
            return null;
        }
        Object roleValue = source.get("role");
        String role = roleValue == null ? "" : String.valueOf(roleValue);
        if (!("user".equals(role) || "assistant".equals(role) || "error".equals(role))) {
            return null;
        }
        String body = text(source.get("text"), MAX_CHAT_TEXT);

        List<Map<String, Object>> photos = new ArrayList<Map<String, Object>>();
        List<Object> listed = asList(source.get("photos"));
        if (listed != null) {
            int taken = 0;
            for (Object item : listed) {
                if (taken >= MAX_CHAT_PHOTOS) {
                    break;
                }
                taken++;
                Map<String, Object> photoRaw = asMap(item);
                if (photoRaw == null) {
                    continue;
                }
                Map<String, Object> photo = new LinkedHashMap<String, Object>();
                photo.put("id", text(photoRaw.get("id"), 80));
                photo.put("name", text(photoRaw.get("name"), 200));
                photo.put("localUrl", text(photoRaw.get("localUrl"), 2000));
                if (!((String) photo.get("id")).isEmpty() || !((String) photo.get("localUrl")).isEmpty()) {
                    photos.add(photo);
                }
            }
        }
        if (body.isEmpty() && photos.isEmpty()) {
            return null;
        }

        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("role", role);
        msg.put("text", body);
        if (!photos.isEmpty()) {
            msg.put("photos", photos);
        }
        // 这条回复后面挂过「一键生成」按钮：重开画布还得挂上
        if ("run".equals(String.valueOf(source.get("action")))) {
            msg.put("action", "run");
        }
        msg.put("at", roundInt(num(source.get("at"), Ids.nowMs(), 0, 1e15)));
        return msg;
    }

    /** 一整段对话：先按条数截，再按总字数砍。 */
    public static List<Map<String, Object>> sanitizeChat(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<Object> listed = asList(raw);
        if (listed == null) {
            return out;
        }
        int from = Math.max(0, listed.size() - MAX_CHAT_MESSAGES);
        for (int i = from; i < listed.size(); i++) {
            Map<String, Object> msg = sanitizeChatMessage(listed.get(i));
            if (msg != null) {
                out.add(msg);
            }
        }
        // 再按总字数砍一次：宁可丢最早的几句，也不能让一份存档无限长大
        int total = 0;
        for (Map<String, Object> msg : out) {
            total += ((String) msg.get("text")).length();
        }
        while (out.size() > 1 && total > MAX_CHAT_TOTAL) {
            total -= ((String) out.get(0).get("text")).length();
            out.remove(0);
        }
        return out;
    }

    /** 对话标题：第一句用户发言（截断），历史列表里靠它认人。 */
    public static String chatTitleOf(Object messagesRaw) {
        List<Object> messages = asList(messagesRaw);
        if (messages == null) {
            return "";
        }
        for (Object item : messages) {
            Map<String, Object> msg = asMap(item);
            if (msg == null) {
                continue;
            }
            if (!"user".equals(String.valueOf(msg.get("role")))) {
                continue;
            }
            String text = msg.get("text") == null ? "" : String.valueOf(msg.get("text")).trim();
            if (text.isEmpty()) {
                continue;
            }
            String line = text.replaceAll("\\s+", " ").trim();
            return line.length() > MAX_CHAT_TITLE ? line.substring(0, MAX_CHAT_TITLE) + "…" : line;
        }
        return "";
    }

    /** 历史列表用：不带整段正文，只给标题 / 条数 / 时间。 */
    public static Map<String, Object> chatMeta(Map<String, Object> chat) {
        List<Object> messages = asList(chat == null ? null : chat.get("messages"));
        String preview = "";
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = asMap(messages.get(i));
                if (msg == null) {
                    continue;
                }
                if ("assistant".equals(String.valueOf(msg.get("role")))) {
                    String text = msg.get("text") == null ? "" : String.valueOf(msg.get("text"));
                    preview = text.length() > 80 ? text.substring(0, 80) : text;
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", chat == null ? null : chat.get("id"));
        String title = chat == null || chat.get("title") == null ? "" : String.valueOf(chat.get("title"));
        if (title.isEmpty()) {
            title = chatTitleOf(chat == null ? null : chat.get("messages"));
        }
        out.put("title", title.isEmpty() ? "一段对话" : title);
        out.put("count", messages == null ? 0 : messages.size());
        out.put("preview", preview);
        long createdAt = chat == null || chat.get("createdAt") == null ? 0L : ((Number) chat.get("createdAt")).longValue();
        out.put("createdAt", createdAt);
        long updatedAt = chat == null || chat.get("updatedAt") == null ? 0L : ((Number) chat.get("updatedAt")).longValue();
        out.put("updatedAt", updatedAt == 0L ? createdAt : updatedAt);
        return out;
    }

    /** 新建一段空白对话。 */
    public static Map<String, Object> newChat() {
        long at = Ids.nowMs();
        Map<String, Object> chat = new LinkedHashMap<String, Object>();
        chat.put("id", Ids.nextId("chat"));
        chat.put("title", "");
        chat.put("messages", new ArrayList<Map<String, Object>>());
        chat.put("createdAt", at);
        chat.put("updatedAt", at);
        return chat;
    }

    /** 一张新画布（数组语义的快照结构）。 */
    public static Map<String, Object> newGraph(String name, int existingCount) {
        long at = Ids.nowMs();
        String clean = name == null ? "" : name.trim();
        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        graph.put("id", Ids.nextId("ncanvas"));
        graph.put("name", clean.isEmpty() ? ("节点画布 " + (existingCount + 1)) : clean);
        graph.put("nodes", new ArrayList<Map<String, Object>>());
        graph.put("edges", new ArrayList<Map<String, Object>>());
        Map<String, Object> viewport = new LinkedHashMap<String, Object>();
        viewport.put("x", 80);
        viewport.put("y", 80);
        viewport.put("zoom", 1);
        graph.put("viewport", viewport);
        graph.put("chats", new ArrayList<Map<String, Object>>());
        graph.put("chatId", null);
        graph.put("createdAt", at);
        graph.put("updatedAt", at);
        return graph;
    }

    /** 空的内联映射，便于构造有序的响应体。 */
    public static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    /** 空的不可变集合（占位用，避免调用方传 null）。 */
    public static Set<String> emptySet() {
        return new HashSet<String>();
    }

    /** 仅供测试/诊断：把常量表摊平成只读副本。 */
    public static Map<String, Object> kindTable() {
        return new HashMap<String, Object>(KIND_EXTS);
    }
}

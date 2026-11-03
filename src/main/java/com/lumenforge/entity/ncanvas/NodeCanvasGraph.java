package com.lumenforge.entity.ncanvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张节点画布的只读视图。
 *
 * <p>对应 Python 版 {@code app/node_canvas.py} 里 {@code new_graph()} 产出的字典：
 * <code>{id, name, nodes[], edges[], viewport{x,y,zoom}, chats[], chatId, createdAt, updatedAt}</code>。
 *
 * <p>为什么是 Map 的包装而不是 JPA 实体：V1 迁移脚本里没有 {@code node_canvases} /
 * {@code node_canvas_chats} / {@code node_assets} 三张表，而 V1 已上线（Flyway checksum 不可改）。
 * 这个模块改用 {@code data/} 下的 JSON 快照文件持久化，字段结构与原版逐字一致；
 * 有了这层包装，业务代码不必到处写 {@code (List<Map<String,Object>>) raw.get("nodes")}。
 */
public final class NodeCanvasGraph {

    /** 一份图的原始字典；所有 getter 都直接读它，保证写回去时字段顺序与原版一致。 */
    private final Map<String, Object> raw;

    private NodeCanvasGraph(Map<String, Object> raw) {
        this.raw = raw;
    }

    public static NodeCanvasGraph of(Map<String, Object> raw) {
        return new NodeCanvasGraph(raw == null ? new LinkedHashMap<String, Object>() : raw);
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public String getId() {
        Object value = raw.get("id");
        return value == null ? "" : String.valueOf(value);
    }

    public String getName() {
        Object value = raw.get("name");
        return value == null ? "" : String.valueOf(value);
    }

    public void setName(String name) {
        raw.put("name", name);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getNodes() {
        Object value = raw.get("nodes");
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        List<Map<String, Object>> created = new ArrayList<Map<String, Object>>();
        raw.put("nodes", created);
        return created;
    }

    public void setNodes(List<Map<String, Object>> nodes) {
        raw.put("nodes", nodes);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEdges() {
        Object value = raw.get("edges");
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        List<Map<String, Object>> created = new ArrayList<Map<String, Object>>();
        raw.put("edges", created);
        return created;
    }

    public void setEdges(List<Map<String, Object>> edges) {
        raw.put("edges", edges);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getViewport() {
        Object value = raw.get("viewport");
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        raw.put("viewport", created);
        return created;
    }

    public void setViewport(Map<String, Object> viewport) {
        raw.put("viewport", viewport);
    }

    /** 历史对话列表（老存档里可能还是单段 {@code chat}，由业务层先迁移）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getChats() {
        Object value = raw.get("chats");
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        List<Map<String, Object>> created = new ArrayList<Map<String, Object>>();
        raw.put("chats", created);
        return created;
    }

    public void setChats(List<Map<String, Object>> chats) {
        raw.put("chats", chats);
    }

    /** 当前正在聊的那段；{@code null} = 刚点了「新对话」，还没开口。 */
    public String getChatId() {
        Object value = raw.get("chatId");
        return value == null ? null : String.valueOf(value);
    }

    public void setChatId(String chatId) {
        raw.put("chatId", chatId);
    }

    public long getCreatedAt() {
        return longOf(raw.get("createdAt"));
    }

    public long getUpdatedAt() {
        return longOf(raw.get("updatedAt"));
    }

    public void setUpdatedAt(long updatedAt) {
        raw.put("updatedAt", updatedAt);
    }

    /** 读数字字段：JSON 回读后可能是 Integer / Long / Double，统一按 long 取。 */
    public static long longOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}

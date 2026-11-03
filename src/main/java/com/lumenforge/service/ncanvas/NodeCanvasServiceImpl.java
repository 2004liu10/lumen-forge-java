package com.lumenforge.service.ncanvas;

import com.lumenforge.common.util.Ids;
import com.lumenforge.entity.ncanvas.NodeCanvasGraph;
import com.lumenforge.entity.ncanvas.NodeCanvasNotFoundException;
import com.lumenforge.mapper.ncanvas.JsonRowStore;
import com.lumenforge.mapper.ncanvas.NodeCanvasStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 节点画布业务实现。
 *
 * <p>逐函数对照 Python 版 {@code app/node_canvas.py} 的 {@code create_graph / update_graph /
 * delete_graph / save_chat / delete_chat / export_graph / export_file_name / import_graph /
 * chats_of}。所有读改写都通过 {@link NodeCanvasStore#mutate} 串行化，
 * 避免两个浏览器标签页同时自动保存把对方的节点冲掉。
 */
@Service
public class NodeCanvasServiceImpl implements NodeCanvasService {

    /** 文件名里只留「字母数字下划线、点、横线」，其余一律换成下划线。 */
    private static final Pattern MEDIA_NAME =
            Pattern.compile("[^\\w\\-.]+", Pattern.UNICODE_CHARACTER_CLASS);

    private final NodeCanvasStore store;

    public NodeCanvasServiceImpl(NodeCanvasStore store) {
        this.store = store;
    }

    @Override
    public Map<String, Object> requireGraph(String canvasId) {
        Map<String, Object> row = NodeCanvasStore.find(store.snapshot(), canvasId);
        if (row == null) {
            throw new NodeCanvasNotFoundException("节点画布不存在");
        }
        return row;
    }

    @Override
    public Map<String, Object> createGraph(final Object name) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> graph = NodeCanvasSanitizer.newGraph(
                        name == null ? null : String.valueOf(name), rows.size());
                rows.add(graph);
                dirty.mark();
                return graph;
            }
        });
    }

    @Override
    public Map<String, Object> updateGraph(final String canvasId, final Map<String, Object> body) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> graph = findOrThrow(rows, canvasId);
                Map<String, Object> cleanBody = body == null ? new LinkedHashMap<String, Object>() : body;

                if (cleanBody.get("name") != null) {
                    String next = NodeCanvasSanitizer.text(cleanBody.get("name"), 120).trim();
                    if (!next.isEmpty()) {
                        graph.put("name", next);
                    }
                }

                boolean hasNodes = cleanBody.get("nodes") != null;
                boolean hasEdges = cleanBody.get("edges") != null;
                if (hasNodes || hasEdges) {
                    // 全量替换：前端自动保存一次提交三项，缺的项沿用旧值
                    Map<String, Object> input = new LinkedHashMap<String, Object>();
                    input.put("nodes", hasNodes ? cleanBody.get("nodes") : graph.get("nodes"));
                    input.put("edges", hasEdges ? cleanBody.get("edges") : graph.get("edges"));
                    input.put("viewport", cleanBody.get("viewport") != null
                            ? cleanBody.get("viewport") : graph.get("viewport"));
                    Map<String, Object> clean = NodeCanvasSanitizer.sanitizeGraph(input);
                    graph.put("nodes", clean.get("nodes"));
                    graph.put("edges", clean.get("edges"));
                    graph.put("viewport", clean.get("viewport"));
                } else if (cleanBody.get("viewport") != null) {
                    // 只挪了视口：不重洗节点，免得把刚编辑的节点又截一遍
                    Map<String, Object> input = new LinkedHashMap<String, Object>();
                    input.put("nodes", graph.get("nodes"));
                    input.put("edges", graph.get("edges"));
                    input.put("viewport", cleanBody.get("viewport"));
                    Map<String, Object> clean = NodeCanvasSanitizer.sanitizeGraph(input);
                    graph.put("viewport", clean.get("viewport"));
                }

                graph.put("updatedAt", Ids.nowMs());
                dirty.mark();
                return graph;
            }
        });
    }

    @Override
    public void deleteGraph(final String canvasId) {
        store.mutate(new JsonRowStore.RowMutation<Boolean>() {
            @Override
            public Boolean apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> graph = findOrThrow(rows, canvasId);
                NodeCanvasStore.removeByIdentity(rows, graph);
                dirty.mark();
                return Boolean.TRUE;
            }
        });
    }

    @Override
    public Map<String, Object> saveChat(final String canvasId, final Map<String, Object> body) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> graph = findOrThrow(rows, canvasId);
                List<Map<String, Object>> chats = chatsOf(graph);
                List<Map<String, Object>> messages =
                        NodeCanvasSanitizer.sanitizeChat(body == null ? null : body.get("messages"));
                String wanted = NodeCanvasSanitizer.text(body == null ? null : body.get("chatId"), 64);
                Map<String, Object> chat = wanted.isEmpty() ? null : findChat(chats, wanted);

                if (chat == null && messages.isEmpty()) {
                    // 「新对话」= 只把指针清掉：新建空记录的话历史里会堆一堆点开啥也没有的空壳
                    graph.put("chatId", null);
                    dirty.mark();
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("ok", Boolean.TRUE);
                    out.put("chatId", null);
                    out.put("sessions", sessionList(chats));
                    return out;
                }

                if (chat == null) {
                    long at = Ids.nowMs();
                    chat = new LinkedHashMap<String, Object>();
                    chat.put("id", Ids.nextId("chat"));
                    chat.put("title", "");
                    chat.put("messages", new ArrayList<Map<String, Object>>());
                    chat.put("createdAt", at);
                    chat.put("updatedAt", at);
                    chats.add(chat);
                }
                chat.put("messages", messages);
                String title = NodeCanvasSanitizer.chatTitleOf(messages);
                if (title.isEmpty()) {
                    title = chat.get("title") == null ? "" : String.valueOf(chat.get("title"));
                }
                chat.put("title", title);
                chat.put("updatedAt", Ids.nowMs());
                graph.put("chatId", chat.get("id"));

                // 超过上限就从最久没动过的那段开始丢（当前这段永远保留）
                if (chats.size() > NodeCanvasSanitizer.MAX_CHATS) {
                    final Map<String, Object> current = chat;
                    List<Map<String, Object>> stale = new ArrayList<Map<String, Object>>();
                    for (Map<String, Object> item : chats) {
                        if (!sameChat(item, current)) {
                            stale.add(item);
                        }
                    }
                    Collections.sort(stale, new Comparator<Map<String, Object>>() {
                        @Override
                        public int compare(Map<String, Object> left, Map<String, Object> right) {
                            return Long.compare(NodeCanvasGraph.longOf(left.get("updatedAt")),
                                    NodeCanvasGraph.longOf(right.get("updatedAt")));
                        }
                    });
                    while (chats.size() > NodeCanvasSanitizer.MAX_CHATS && !stale.isEmpty()) {
                        Map<String, Object> dropped = stale.remove(0);
                        removeByIdentity(chats, dropped);
                    }
                }

                dirty.mark();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                out.put("chatId", chat.get("id"));
                out.put("chat", NodeCanvasSanitizer.chatMeta(chat));
                out.put("sessions", sessionList(chats));
                return out;
            }
        });
    }

    @Override
    public Map<String, Object> deleteChat(final String canvasId, final String chatId) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> graph = findOrThrow(rows, canvasId);
                List<Map<String, Object>> chats = chatsOf(graph);
                Map<String, Object> chat = findChat(chats, chatId);
                if (chat == null) {
                    throw new NodeCanvasNotFoundException("这段对话不存在");
                }
                removeByIdentity(chats, chat);
                Object current = graph.get("chatId");
                if (current != null && chatId != null && chatId.equals(String.valueOf(current))) {
                    graph.put("chatId", null);
                }
                dirty.mark();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                out.put("sessions", sessionList(chats));
                return out;
            }
        });
    }

    @Override
    public Map<String, Object> exportPayload(String canvasId) {
        Map<String, Object> graph = requireGraph(canvasId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("format", "lumen-forge-node-canvas");
        out.put("version", 1);
        out.put("name", graph.get("name"));
        out.put("nodes", graph.get("nodes"));
        out.put("edges", graph.get("edges"));
        out.put("viewport", graph.get("viewport"));
        out.put("exportedAt", NodeCanvasJson.isoNow());
        return out;
    }

    @Override
    public String exportFileName(String canvasId) {
        Map<String, Object> graph = requireGraph(canvasId);
        Object name = graph.get("name");
        String raw = name == null ? "" : String.valueOf(name);
        String safe = MEDIA_NAME.matcher(raw).replaceAll("_");
        if (safe.isEmpty()) {
            safe = "node-canvas";
        }
        return safe + ".json";
    }

    @Override
    public Map<String, Object> importGraph(final Object raw) {
        final Object payload = raw instanceof Map && ((Map<?, ?>) raw).get("graph") != null
                ? ((Map<?, ?>) raw).get("graph") : raw;
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> source = NodeCanvasJson.asMap(payload);
                if (source == null) {
                    throw new IllegalArgumentException("文件内容不是有效的画布 JSON");
                }
                Map<String, Object> clean = NodeCanvasSanitizer.sanitizeGraph(source);
                Object nodes = clean.get("nodes");
                if (nodes instanceof List && ((List<?>) nodes).isEmpty()) {
                    throw new IllegalArgumentException("这份 JSON 里没有可用的节点");
                }
                String name = NodeCanvasSanitizer.text(source.get("name"), 120);
                if (name.isEmpty()) {
                    name = "导入的画布";
                }
                Map<String, Object> graph = NodeCanvasSanitizer.newGraph(name, rows.size());
                graph.put("nodes", clean.get("nodes"));
                graph.put("edges", clean.get("edges"));
                graph.put("viewport", clean.get("viewport"));
                rows.add(graph);
                dirty.mark();
                return graph;
            }
        });
    }

    @Override
    public List<Map<String, Object>> snapshots() {
        List<Map<String, Object>> rows = store.snapshot();
        List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>(rows);
        // 首屏按「最近改过的在前」更好用；Python 那边是存档顺序，但两条都是稳定数组，
        // 前端只遍历不依赖次序，所以这里统一成 createdAt 倒序
        Collections.sort(ordered, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long a = NodeCanvasGraph.longOf(left.get("createdAt"));
                long b = NodeCanvasGraph.longOf(right.get("createdAt"));
                return b < a ? -1 : (b > a ? 1 : 0);
            }
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(ordered.size());
        for (Map<String, Object> row : ordered) {
            // 浅拷贝一层：调用方（StateController）拼大对象时改不到存档里的行
            out.add(new LinkedHashMap<String, Object>(row));
        }
        return out;
    }

    // ------------------------------------------------------------ 内部

    private Map<String, Object> findOrThrow(List<Map<String, Object>> rows, String canvasId) {
        Map<String, Object> graph = NodeCanvasStore.find(rows, canvasId);
        if (graph == null) {
            throw new NodeCanvasNotFoundException("节点画布不存在");
        }
        return graph;
    }

    /**
     * 老存档迁移：以前一张画布只有一段 {@code graph.chat}，现在是 {@code chats[] + chatId}。
     *
     * <p>迁移只做一次，做完把旧字段删掉，免得两套数据同时存在互相打架。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chatsOf(Map<String, Object> graph) {
        Object existing = graph.get("chats");
        if (existing instanceof List) {
            return (List<Map<String, Object>>) existing;
        }
        List<Map<String, Object>> chats = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> legacy = NodeCanvasSanitizer.sanitizeChat(graph.get("chat"));
        if (!legacy.isEmpty()) {
            long at = NodeCanvasGraph.longOf(graph.get("chatAt"));
            if (at == 0L) {
                at = Ids.nowMs();
            }
            Map<String, Object> chat = new LinkedHashMap<String, Object>();
            chat.put("id", Ids.nextId("chat"));
            chat.put("title", NodeCanvasSanitizer.chatTitleOf(legacy));
            chat.put("messages", legacy);
            chat.put("createdAt", at);
            chat.put("updatedAt", at);
            chats.add(chat);
        }
        graph.put("chats", chats);
        graph.put("chatId", chats.isEmpty() ? null : chats.get(0).get("id"));
        graph.remove("chat");
        graph.remove("chatAt");
        return chats;
    }

    private Map<String, Object> findChat(List<Map<String, Object>> chats, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return null;
        }
        for (Map<String, Object> chat : chats) {
            Object id = chat.get("id");
            if (id != null && chatId.equals(String.valueOf(id))) {
                return chat;
            }
        }
        return null;
    }

    /** 两段对话是不是同一条：比对象身份，避免内容相同的两段被当成同一段。 */
    private boolean sameChat(Map<String, Object> left, Map<String, Object> right) {
        return left == right;
    }

    private boolean removeByIdentity(List<Map<String, Object>> chats, Map<String, Object> chat) {
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i) == chat) {
                chats.remove(i);
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> sessionList(List<Map<String, Object>> chats) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> chat : chats) {
            out.add(NodeCanvasSanitizer.chatMeta(chat));
        }
        return out;
    }
}

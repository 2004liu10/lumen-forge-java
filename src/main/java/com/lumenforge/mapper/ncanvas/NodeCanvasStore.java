package com.lumenforge.mapper.ncanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.storage.StoragePaths;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 节点画布的快照表（{@code data/node-canvases.json}）。
 *
 * <p>对应 Python 版 {@code store.node_canvases}：一个数组，每项是一整张图的 payload。
 * 每张图里的 {@code chats[]} 也在这份 payload 里，所以不需要单独的 chat 表。
 */
@Component
public class NodeCanvasStore extends JsonRowStore {

    public NodeCanvasStore(StoragePaths paths, ObjectMapper mapper) {
        super(paths, mapper, "node-canvases.json");
    }

    /** 按 id 找一行；找不到返回 {@code null}。 */
    public static Map<String, Object> find(List<Map<String, Object>> rows, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            Object value = row.get("id");
            if (value != null && id.equals(String.valueOf(value))) {
                return row;
            }
        }
        return null;
    }

    /** 按对象身份移除（不能用 {@code List.remove}，Map 的 equals 会误删同内容的另一行）。 */
    public static boolean removeByIdentity(List<Map<String, Object>> rows, Map<String, Object> row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row) {
                rows.remove(i);
                return true;
            }
        }
        return false;
    }
}

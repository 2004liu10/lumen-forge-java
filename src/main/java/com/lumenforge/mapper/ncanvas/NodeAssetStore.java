package com.lumenforge.mapper.ncanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.storage.StoragePaths;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 本地素材库的快照表（{@code data/node-assets.json}）。
 *
 * <p>对应 Python 版 {@code store.node_assets}：一行一份素材元数据，删除只置
 * {@code deletedAt}（进回收站），永久删除才摘行并尝试删盘。
 */
@Component
public class NodeAssetStore extends JsonRowStore {

    public NodeAssetStore(StoragePaths paths, ObjectMapper mapper) {
        super(paths, mapper, "node-assets.json");
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

    /** 按对象身份移除（Map 的 equals 会把内容相同的另一行一起删掉）。 */
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

package com.lumenforge.service.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材模块自己的 JSON 小工具。
 *
 * <p>{@code com.lumenforge.storage.JsonSupport} 是包内私有的，只给 {@code StoragePaths} 用，
 * 这里不能复用，于是另起一个 —— 但语义必须与 Python 版 {@code app/materials.py} 里那几个
 * 递归取值函数一致，否则同一条接口返回的数据会解析出不同的素材。
 */
final class MaterialJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private MaterialJson() {
    }

    /** 解析成 Map；失败返回 null（调用方自己决定是报错还是当空）。 */
    static Map<String, Object> parseObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            MapType type = MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class);
            Map<String, Object> parsed = MAPPER.readValue(text, type);
            return parsed;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 解析成 List；失败返回 null。 */
    static List<Object> parseArray(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            CollectionType type = MAPPER.getTypeFactory().constructCollectionType(ArrayList.class, Object.class);
            return MAPPER.readValue(text, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 任意对象写成 JSON 串；写不出来给兜底值。 */
    static String write(Object value, String fallback) {
        if (value == null) return fallback;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * 按点路径取值，支持数组下标：{@code data.list} / {@code data.0.url}。
     *
     * <p>对应 Python {@code _get_by_path()}：任何一段取不到就返回 null，不抛错。
     */
    static Object byPath(Object node, String dotted) {
        if (node == null || dotted == null || dotted.trim().isEmpty()) return null;
        Object current = node;
        for (String part : dotted.trim().split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else if (current instanceof List) {
                int index = parseInt(part, -1);
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) return null;
                current = list.get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 递归找「元素全是对象」的数组，返回其中元素最多的一个（同长度时取更浅的）。
     *
     * <p>对应 Python {@code _find_object_arrays()}：素材接口千奇百怪，不指定路径时就靠它兜底。
     */
    static List<Object> findObjectArray(Object node) {
        List<Object[]> found = new ArrayList<Object[]>();
        collectObjectArrays(node, 0, found);
        List<Object> best = null;
        int bestLength = -1;
        int bestDepth = Integer.MAX_VALUE;
        for (Object[] candidate : found) {
            @SuppressWarnings("unchecked")
            List<Object> array = (List<Object>) candidate[0];
            int depth = (Integer) candidate[1];
            int length = array.size();
            if (length > bestLength || (length == bestLength && depth < bestDepth)) {
                best = array;
                bestLength = length;
                bestDepth = depth;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static void collectObjectArrays(Object node, int depth, List<Object[]> found) {
        if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            if (!list.isEmpty()) {
                boolean allObjects = true;
                for (Object item : list) {
                    if (!(item instanceof Map)) {
                        allObjects = false;
                        break;
                    }
                }
                if (allObjects) found.add(new Object[]{list, depth});
            }
            for (Object item : list) {
                collectObjectArrays(item, depth + 1, found);
            }
        } else if (node instanceof Map) {
            for (Object value : ((Map<String, Object>) node).values()) {
                collectObjectArrays(value, depth + 1, found);
            }
        }
    }

    /**
     * 递归收集所有字符串值（广度优先的插入序），用于「地址字段没填」时的最后兜底。
     *
     * <p>对应 Python {@code _collect_strings()}。
     */
    @SuppressWarnings("unchecked")
    static List<String> collectStrings(Object node) {
        List<String> out = new ArrayList<String>();
        if (node instanceof String) {
            String text = ((String) node).trim();
            if (!text.isEmpty()) out.add(text);
            return out;
        }
        if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                out.addAll(collectStrings(item));
            }
            return out;
        }
        if (node instanceof Map) {
            for (Object value : ((Map<String, Object>) node).values()) {
                out.addAll(collectStrings(value));
            }
        }
        return out;
    }

    /** 宽松转 int，转不动给兜底值。 */
    static int parseInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return fallback;
            double parsed = Double.parseDouble(text);
            return (int) parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

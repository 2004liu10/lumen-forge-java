package com.lumenforge.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储层内部用的小型 JSON 桥。
 *
 * <p>只给 {@link StoragePaths} 读写路径配置用，避免为了一个配置文件把整个 ObjectMapper
 * 注进构造函数（那个类在容器启动早期就要可用）。
 */
final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return MAPPER.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (Exception err) {
            throw new IllegalArgumentException("存储路径配置不是合法 JSON", err);
        }
    }

    static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception err) {
            throw new IllegalArgumentException("无法序列化存储路径配置", err);
        }
    }
}

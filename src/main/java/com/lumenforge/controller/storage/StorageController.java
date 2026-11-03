package com.lumenforge.controller.storage;

import com.lumenforge.storage.StoragePaths;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储位置设置 —— 「设置 → 存储位置」的全部后端。
 *
 * <p>对应 Python 版 {@code app/api/storage_api.py}，只有一条路由：
 * 改某个媒体目录（{@code {key, dir}}），或恢复默认（{@code {reset: true}}）。
 * {@code dir} 传空串等于恢复默认，这条规则在 {@link StoragePaths#setDir} 里。
 *
 * <p>响应把 {@code ok} 与该项结果摊平，再附一份全部目录的快照，
 * 前端据此一次性刷新整块设置面板。
 */
@RestController
@RequestMapping("/api")
public class StorageController {

    private final StoragePaths paths;

    public StorageController(StoragePaths paths) {
        this.paths = paths;
    }

    @PostMapping("/storage")
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<String, Object>() : body;
        String key = safe.get("key") == null ? "" : String.valueOf(safe.get("key"));
        Map<String, Object> result;
        try {
            if (Boolean.TRUE.equals(safe.get("reset")) || "true".equals(String.valueOf(safe.get("reset")))) {
                result = paths.resetDir(key);
            } else {
                Object dir = safe.get("dir");
                result = paths.setDir(key, dir == null ? "" : String.valueOf(dir));
            }
        } catch (IllegalArgumentException err) {
            // 未知分类等业务错误，交给 GlobalExceptionHandler 翻 400
            throw err;
        } catch (RuntimeException err) {
            // 建目录失败之类的运行时错误，Python 版也是回 400，这里统一成业务错误
            throw new IllegalArgumentException(err.getMessage() == null ? "存储路径设置失败" : err.getMessage(), err);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(result);
        out.put("items", paths.snapshot());
        return out;
    }
}

package com.lumenforge.controller.material;

import com.lumenforge.service.material.MaterialSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 素材分类（接口管理）与全局线路池的 HTTP 模块。
 *
 * <p>对应 Python 版 {@code app/api/materials_api.py} 的分类那一段。
 * 分类列表没有单独路由 —— 它跟在线路池一样从 {@code GET /api/state} 里一起下发。
 *
 * <p>Python 版删分类用的是 404，而 {@code GlobalExceptionHandler} 只把
 * IllegalArgumentException 翻成 400，所以 404 要在这里自己产出。
 */
@RestController
@RequestMapping("/api")
public class MaterialSourceController {

    private final MaterialSourceService service;

    public MaterialSourceController(MaterialSourceService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 分类

    @PostMapping("/material-sources")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return service.create(body);
    }

    /**
     * 改分类。分类不存在时按 Python 版语义回 404；而改字段本身校验失败
     * （比如线路地址不合法）仍是 400，所以这里只能单独拦「找不到」这一步。
     */
    @PatchMapping("/material-sources/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        try {
            service.require(id);
        } catch (IllegalArgumentException exception) {
            return notFound(exception);
        }
        return ResponseEntity.ok(service.update(id, body));
    }

    @DeleteMapping("/material-sources/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        try {
            service.delete(id);
        } catch (IllegalArgumentException exception) {
            return notFound(exception);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }

    /** Python 版这三处都用 404，而 GlobalExceptionHandler 只会给 400。 */
    private ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException exception) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("error", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // ------------------------------------------------------------------ 线路池

    @PutMapping("/material-line-pool")
    public Map<String, Object> updateLinePool(@RequestBody(required = false) Map<String, Object> body) {
        return service.updateLinePool(body);
    }

    // ------------------------------------------------------------------ 取素材

    /** {@code refresh=1} 跳过 3 分钟缓存强制重抓；分类不存在时 Python 版回 404。 */
    @GetMapping("/material-sources/{id}/items")
    public ResponseEntity<Map<String, Object>> items(@PathVariable String id,
                                                     @RequestParam(value = "refresh", required = false) String refresh) {
        try {
            service.require(id);
        } catch (IllegalArgumentException exception) {
            return notFound(exception);
        }
        return ResponseEntity.ok(service.items(id, "1".equals(refresh)));
    }
}

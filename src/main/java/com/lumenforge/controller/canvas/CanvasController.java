package com.lumenforge.controller.canvas;

import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.service.canvas.CanvasService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 画布 HTTP 模块：只负责请求绑定与响应，参数检查编排委托给业务层。 */
@RestController
@RequestMapping("/api/canvases")
public class CanvasController {
    private final CanvasService service;

    public CanvasController(CanvasService service) {
        this.service = service;
    }

    @GetMapping
    public List<java.util.Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public java.util.Map<String, Object> create(@RequestBody java.util.Map<String, Object> request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public java.util.Map<String, Object> update(@PathVariable String id, @RequestBody java.util.Map<String, Object> request) {
        return service.update(id, request);
    }

    /** AI 应用通过 RunningHub 查询 schema；工作流则从用户粘贴的 api.json 提取标量输入。 */
    @PostMapping("/{id}/inspect")
    public Map<String, Object> inspect(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return service.inspect(id, body == null ? java.util.Collections.<String, Object>emptyMap() : body);
    }

    @PostMapping("/{id}/assets/{key}")
    public Map<String, Object> uploadAsset(@PathVariable String id, @PathVariable String key,
                                            @RequestBody byte[] bytes,
                                            @RequestHeader(value = "x-filename", required = false) String filename,
                                            @RequestHeader(value = "content-type", required = false) String contentType) {
        String decodedName = filename;
        if (filename != null) {
            try { decodedName = java.net.URLDecoder.decode(filename, "UTF-8"); }
            catch (Exception ignored) { }
        }
        return service.uploadAsset(id, key, bytes, decodedName, contentType);
    }

    @DeleteMapping("/{id}/assets/{key}")
    public Map<String, Object> removeAsset(@PathVariable String id, @PathVariable String key) {
        return service.removeAsset(id, key);
    }

    /** 额外参数：整段 JSON 文本，必须是数组，提交时原样带给 RunningHub。 */
    @PostMapping("/{id}/extra-params")
    public Map<String, Object> setExtraParams(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return service.setExtraParams(id, body == null ? java.util.Collections.<String, Object>emptyMap() : body);
    }

    @DeleteMapping("/{id}")
    public java.util.Map<String, Boolean> delete(@PathVariable String id) {
        service.delete(id);
        return java.util.Collections.singletonMap("ok", true);
    }

}





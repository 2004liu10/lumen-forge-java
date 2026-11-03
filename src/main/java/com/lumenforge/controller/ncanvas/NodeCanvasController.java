package com.lumenforge.controller.ncanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.service.ncanvas.NodeCanvasJson;
import com.lumenforge.service.ncanvas.NodeCanvasService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无限画布的「画布」接口。
 *
 * <p>逐条对照 Python 版 {@code app/api/node_canvas_api.py} 的 {@code /api/node-canvases} 路由：
 * 请求体一律先按 {@code json_body} 的语义解析（空体当 {@code {}}、坏 JSON 报 400、数组包成
 * {@code __array__}、非对象报 400），再交给业务层。
 */
@RestController
@RequestMapping("/api/node-canvases")
public class NodeCanvasController extends NodeCanvasExceptionSupport {

    private final NodeCanvasService service;
    private final ObjectMapper mapper;

    public NodeCanvasController(NodeCanvasService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 新建画布，直接把整张图还给前端。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) String body) {
        Map<String, Object> payload = NodeCanvasJson.readBody(mapper, body);
        return service.createGraph(payload.get("name"));
    }

    /**
     * 自动保存。
     *
     * <p>先校验画布存在（不存在直接 404），再看请求体 —— 原版就是这个顺序，前端拿到 404
     * 才知道画布被删了，不该因为请求体写坏而误判成 400。
     */
    @PatchMapping("/{canvasId}")
    public Map<String, Object> update(@PathVariable String canvasId,
                                      @RequestBody(required = false) String body) {
        service.requireGraph(canvasId);
        Map<String, Object> updated = service.updateGraph(canvasId, NodeCanvasJson.readBody(mapper, body));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("updatedAt", updated.get("updatedAt"));
        return out;
    }

    @DeleteMapping("/{canvasId}")
    public Map<String, Object> delete(@PathVariable String canvasId) {
        service.requireGraph(canvasId);
        service.deleteGraph(canvasId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    /**
     * 导出成文件。
     *
     * <p>{@code Content-Disposition} 用 RFC 5987 的 {@code filename*} 形式：画布名是中文，
     * 直接塞进 {@code filename=} 会被浏览器丢成乱码，所以整串 percent-encode（连 {@code /}
     * 也编，见 {@code quote(str(value), safe="")}）。
     */
    @GetMapping("/{canvasId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String canvasId) {
        service.requireGraph(canvasId);
        Map<String, Object> payload = service.exportPayload(canvasId);
        String fileName = service.exportFileName(canvasId);
        byte[] bytes = NodeCanvasJson.writeBytes(mapper, payload);
        HttpHeaders headers = new HttpHeaders();
        // 原版写死的就是这一串（带空格、小写 utf-8），别让 Spring 重排成 application/json;charset=UTF-8
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + NodeCanvasJson.quoteRfc5987(fileName));
        return new ResponseEntity<byte[]>(bytes, headers, HttpStatus.OK);
    }

    /** 编导聊天：一张画布下可以存多段对话，这里是「提交某一段」。 */
    @PostMapping("/{canvasId}/chat")
    public Map<String, Object> saveChat(@PathVariable String canvasId,
                                        @RequestBody(required = false) String body) {
        service.requireGraph(canvasId);
        return service.saveChat(canvasId, NodeCanvasJson.readBody(mapper, body));
    }

    @DeleteMapping("/{canvasId}/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable String canvasId, @PathVariable String chatId) {
        service.requireGraph(canvasId);
        return service.deleteChat(canvasId, chatId);
    }

    /**
     * 导入一份导出的 JSON。
     *
     * <p>{@code raw = body.get("graph") if body.get("graph") is not None else body}：既吃
     * {@code {"graph": {...}}} 这种包了一层的，也吃直接给图的。整个文件是个数组时原版会
     * 先把它包成 {@code __array__} 再判 400，所以这里也要单独认这个键。
     */
    @PostMapping("/import")
    public Map<String, Object> importGraph(@RequestBody(required = false) String body) {
        Map<String, Object> payload = NodeCanvasJson.readBody(mapper, body);
        Object raw = payload.get("graph") == null ? payload : payload.get("graph");
        if (payload.get("__array__") != null) {
            throw new IllegalArgumentException("文件内容不是有效的画布 JSON");
        }
        return service.importGraph(raw);
    }
}

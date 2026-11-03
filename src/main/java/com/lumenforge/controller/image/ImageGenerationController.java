package com.lumenforge.controller.image;

import com.lumenforge.service.image.ImageGenerationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;

/** 图片生成 HTTP 模块；请求校验和业务编排交给 ImageGenerationService。 */
@RestController
@RequestMapping("/api")
public class ImageGenerationController {
    private final ImageGenerationService service;

    public ImageGenerationController(ImageGenerationService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 模型

    @GetMapping("/image-models")
    public List<Map<String, Object>> models() {
        return service.listModels();
    }

    @PostMapping("/image-models")
    public Map<String, Object> createModel(@RequestBody Map<String, Object> body) {
        return service.createModel(body);
    }

    @PatchMapping("/image-models/{id}")
    public Map<String, Object> updateModel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.updateModel(id, body);
    }

    @DeleteMapping("/image-models/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id) {
        return service.deleteModel(id);
    }

    // ---------------------------------------------------------------- 任务

    @GetMapping("/image-tasks")
    public List<Map<String, Object>> tasks(@RequestParam(defaultValue = "120") int limit) {
        return service.listTasks(limit);
    }

    @PostMapping("/image-tasks")
    public Map<String, Object> createTask(@RequestBody Map<String, Object> body) {
        return service.createTask(body);
    }

    @PostMapping("/image-tasks/{id}/retry")
    public Map<String, Object> retryTask(@PathVariable String id) {
        return service.retryTask(id);
    }

    @PostMapping("/image-tasks/{id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable String id) {
        return service.cancelTask(id);
    }

    @DeleteMapping("/image-tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable String id) {
        return service.deleteTask(id);
    }

    /** 把历史任务里的参考图快照还原成可用的参考图，供「再次使用这组参数」。 */
    @PostMapping("/image-tasks/{id}/reuse")
    public Map<String, Object> reuseRefs(@PathVariable String id) {
        return service.reuseTaskRefs(id);
    }

    /** 把某张结果图加进参考图库。 */
    @PostMapping("/image-tasks/{id}/ref")
    public Map<String, Object> imageToRef(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return service.taskImageToRef(id, body == null ? new java.util.LinkedHashMap<String, Object>() : body);
    }

    /** 把某张结果图直接送进画布的图片输入参数。 */
    @PostMapping("/image-tasks/{id}/to-canvas")
    public Map<String, Object> imageToCanvas(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return service.taskImageToCanvas(id, body == null ? new java.util.LinkedHashMap<String, Object>() : body);
    }

    /** 导入外部（豆包临时目录）已下载的图片，走同一套落盘与任务记录。 */
    @PostMapping("/image-import")
    public Map<String, Object> importImage(@RequestBody Map<String, Object> body) {
        return service.importImage(body);
    }

    // ---------------------------------------------------------------- 参考图库

    @GetMapping("/image-refs")
    public List<Map<String, Object>> refs() {
        return service.listRefs();
    }

    /**
     * 参考图上传走裸二进制请求体（前端直接 post File），文件名由调用方放在请求头或查询参数里。
     * 这里不做 multipart 解析，避免为了一个端点引入 multipart 配置。
     *
     * <p>文件名请求头与 Python 版 {@code app/api/deps.py:79 original_name_from} 一致，读的是
     * {@code x-filename}（全项目上传都发这个头），{@code Content-Disposition} 与查询参数 {@code name}
     * 只是兜底。注意不是 {@code x-file-name} —— 少个 {@code name} 就读不到。
     */
    @PostMapping("/image-refs")
    public Map<String, Object> uploadRef(@RequestBody(required = false) byte[] buffer,
                                         @RequestHeader(value = "x-filename", required = false) String headerName,
                                         @RequestHeader(value = "Content-Disposition", required = false) String disposition,
                                         @RequestParam(value = "name", required = false) String queryName,
                                         @RequestHeader(value = "Content-Type", required = false) String contentType) {
        String originalName = firstNonEmpty(headerName, dispositionName(disposition), queryName);
        if (originalName.isEmpty()) {
            originalName = "ref.png";
        }
        return service.uploadRef(buffer, decodeFileName(originalName), contentType);
    }

    @DeleteMapping("/image-refs/{id}")
    public Map<String, Object> deleteRef(@PathVariable String id) {
        return service.deleteRef(id);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 文件名可能带中文，浏览器会按 RFC 5987 做百分号编码，这里解一次。 */
    private String decodeFileName(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException err) {
            return raw;
        }
    }

    /** 从 {@code Content-Disposition} 里取 {@code filename*} 或 {@code filename}，等价 Python {@code original_name_from} 的兜底段。 */
    private String dispositionName(String disposition) {
        if (disposition == null || disposition.isEmpty()) {
            return "";
        }
        String marker = disposition.contains("filename*=") ? "filename*=" : (disposition.contains("filename=") ? "filename=" : "");
        if (marker.isEmpty()) {
            return "";
        }
        String piece = disposition.substring(disposition.indexOf(marker) + marker.length());
        int semicolon = piece.indexOf(';');
        if (semicolon >= 0) {
            piece = piece.substring(0, semicolon);
        }
        piece = piece.trim();
        if (piece.length() >= 2 && piece.startsWith("\"") && piece.endsWith("\"")) {
            piece = piece.substring(1, piece.length() - 1);
        }
        if (piece.toLowerCase().startsWith("utf-8''")) {
            piece = piece.substring(7);
        }
        return piece;
    }
}

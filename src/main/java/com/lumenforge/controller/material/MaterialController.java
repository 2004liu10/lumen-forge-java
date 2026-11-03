package com.lumenforge.controller.material;

import com.lumenforge.common.util.Texts;
import com.lumenforge.service.material.MaterialCanvasService;
import com.lumenforge.service.material.MaterialDownloadService;
import com.lumenforge.service.material.MaterialImageSearch;
import com.lumenforge.service.material.MaterialSourceService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 素材取用相关的 HTTP 模块：随机图、图源搜索、排行榜、下载、送画布、转参考图。
 *
 * <p>对应 Python 版 {@code app/api/materials_api.py} 的取素材那一段。
 * 所有「没搜到」「地址不合法」这类业务报错都是 IllegalArgumentException，
 * 由 {@code GlobalExceptionHandler} 统一翻成 400。
 */
@RestController
@RequestMapping("/api")
public class MaterialController {

    private final MaterialImageSearch images;
    private final MaterialDownloadService downloads;
    private final MaterialCanvasService canvas;
    private final MaterialSourceService sources;

    public MaterialController(MaterialImageSearch images,
                              MaterialDownloadService downloads,
                              MaterialCanvasService canvas,
                              MaterialSourceService sources) {
        this.images = images;
        this.downloads = downloads;
        this.canvas = canvas;
        this.sources = sources;
    }

    // ------------------------------------------------------------------ 随机图

    /** 随机图一批（只回地址，不落盘）；要显示时前端再走 {@code /rand/{token}}。 */
    @GetMapping("/materials/random")
    public Map<String, Object> random(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "16") int limit) {
        return images.random(page, limit);
    }

    /**
     * 把一批随机素材里的一份吐出来，前端直接当图片/视频地址用。
     *
     * <p>过期按 Python 版语义回 404（这里不能靠 GlobalExceptionHandler，它只会给 400）。
     */
    @GetMapping("/materials/rand/{token}")
    public ResponseEntity<Object> randomFile(@PathVariable String token) {
        Map<String, Object> snap = downloads.readSnapshot(token);
        if (snap == null) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("error", "这一份随机素材已过期，点「刷新」重新拉一批");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
        byte[] buffer = (byte[]) snap.get("buffer");
        String mime = Texts.str(snap, "mime");
        MediaType mediaType;
        try {
            mediaType = mime.isEmpty() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mime);
        } catch (RuntimeException ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(30, TimeUnit.MINUTES).cachePublic())
                .body((Object) buffer);
    }

    // ------------------------------------------------------------------ 图源

    @GetMapping("/materials/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "pixiv") String source,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "24") int limit) {
        return images.search(q, source, page, limit);
    }

    @GetMapping("/materials/ranking")
    public Map<String, Object> ranking(@RequestParam(defaultValue = "day") String mode,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "24") int limit) {
        return images.ranking(mode, page, limit);
    }

    // ------------------------------------------------------------------ 下载

    /** 下载一份素材到 {@code materials/}；只回前端需要的三个字段。 */
    @PostMapping("/materials/download")
    public Map<String, Object> download(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<String, Object>() : body;
        String kind = "image".equals(Texts.str(safe, "kind")) ? "image" : "video";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String sourceId = Texts.str(safe, "sourceId");
        if (!sourceId.isEmpty()) {
            headers = sources.headers(sources.require(sourceId));
        }
        Map<String, Object> file = downloads.download(Texts.str(safe, "url"), kind,
                "", headers, Texts.asBool(safe.get("unique"), false));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("fileName", file.get("fileName"));
        out.put("localUrl", file.get("localUrl"));
        out.put("size", file.get("size"));
        return out;
    }

    // ------------------------------------------------------------------ 投送

    /** 把素材挂到画布的视频参数上，并顺手收走带的抖音话题。 */
    @PostMapping("/materials/to-canvas")
    public Map<String, Object> toCanvas(@RequestBody(required = false) Map<String, Object> body) {
        return canvas.toCanvas(body == null ? new LinkedHashMap<String, Object>() : body);
    }

    /** 把素材登记成图生图参考图（只登记路径，不复制文件）。 */
    @PostMapping("/materials/to-image-ref")
    public Map<String, Object> toImageRef(@RequestBody(required = false) Map<String, Object> body) {
        return canvas.toImageRef(body == null ? new LinkedHashMap<String, Object>() : body);
    }
}

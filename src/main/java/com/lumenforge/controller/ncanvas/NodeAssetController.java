package com.lumenforge.controller.ncanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.entity.ncanvas.NodeCanvasTooLargeException;
import com.lumenforge.service.ncanvas.NodeAssetService;
import com.lumenforge.service.ncanvas.NodeCanvasFiles;
import com.lumenforge.service.ncanvas.NodeCanvasJson;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 无限画布的「素材库」接口。
 *
 * <p>逐条对照 Python 版 {@code app/api/node_canvas_api.py} 的 {@code /api/node-assets} 路由。
 *
 * <p>上传走原始二进制而不是 multipart：原版就是 {@code express.raw({limit:'500mb'})}，
 * 文件名与 MIME 由 {@code x-filename} / {@code x-mime} 两个头带过来，前端已经在这么发了。
 */
@RestController
@RequestMapping("/api/node-assets")
public class NodeAssetController extends NodeCanvasExceptionSupport {

    private final NodeAssetService service;
    private final ObjectMapper mapper;

    public NodeAssetController(NodeAssetService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 两栏列表：{@code items}（在用）+ {@code trash}（回收站）。 */
    @GetMapping
    public Map<String, Object> list() {
        return service.assetLists();
    }

    /**
     * 上传素材。
     *
     * <p>体积先判再干活：超过 500MB 直接 413，不落盘也不建记录。文件名取
     * {@code x-filename} 头，缺了才看 {@code Content-Disposition}，都没有就用「素材」。
     */
    @PostMapping
    public Map<String, Object> upload(@RequestBody(required = false) byte[] body,
                                      @RequestHeader(value = "x-filename", required = false) String fileName,
                                      @RequestHeader(value = "x-mime", required = false) String mimeType,
                                      @RequestHeader(value = "Content-Disposition", required = false) String disposition) {
        byte[] buffer = body == null ? new byte[0] : body;
        if (buffer.length == 0) {
            throw new IllegalArgumentException("没有收到文件内容");
        }
        if (buffer.length > NodeAssetService.MAX_ASSET_BYTES) {
            throw new NodeCanvasTooLargeException(tooLargeMessage(buffer.length));
        }
        String name = originalName(fileName, disposition, "素材");
        return service.registerUpload(buffer, name, mimeType == null ? "" : mimeType);
    }

    /** 把磁盘上已有的文件登记进素材库（不复制字节）。 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody(required = false) String body) {
        return service.registerExisting(NodeCanvasJson.readBody(mapper, body));
    }

    @PostMapping("/{assetId}/to-image-ref")
    public Map<String, Object> toImageRef(@PathVariable String assetId) {
        return service.toImageRef(assetId);
    }

    @PatchMapping("/{assetId}")
    public Map<String, Object> patch(@PathVariable String assetId,
                                     @RequestBody(required = false) String body) {
        // 顺序同原版：先确认这条素材还在（不在就 404），再看请求体写没写坏
        service.requireAsset(assetId);
        return service.patchAsset(assetId, NodeCanvasJson.readBody(mapper, body));
    }

    /** 删除 = 先进回收站（文件还在，随时能恢复）。 */
    @DeleteMapping("/{assetId}")
    public Map<String, Object> trash(@PathVariable String assetId) {
        return service.trashAsset(assetId);
    }

    @PostMapping("/{assetId}/restore")
    public Map<String, Object> restore(@PathVariable String assetId) {
        return service.restoreAsset(assetId);
    }

    /** 永久删除：摘掉记录，并在这个文件没人引用时删盘。 */
    @DeleteMapping("/{assetId}/purge")
    public Map<String, Object> purge(@PathVariable String assetId) {
        return service.purgeAsset(assetId);
    }

    @PostMapping("/clear-trash")
    public Map<String, Object> clearTrash() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> result = service.clearTrash();
        out.put("ok", Boolean.TRUE);
        out.put("count", result.get("count"));
        out.put("removed", result.get("removed"));
        return out;
    }

    // ------------------------------------------------------------ 内部

    /**
     * 文件名来源，对齐原版 {@code original_name_from()}。
     *
     * <p>顺序是 {@code x-filename} → {@code Content-Disposition} 的 {@code filename*} →
     * 同一个头的 {@code filename=} → 兜底。两个头都是 percent-encode 过的（中文名必须编，
     * HTTP 头不能直接放非 ASCII），所以都要解码。
     */
    private String originalName(String xFilename, String disposition, String fallback) {
        if (xFilename != null && !xFilename.trim().isEmpty()) {
            return percentDecodeOrRaw(xFilename.trim());
        }
        if (disposition != null && !disposition.isEmpty()) {
            String value = null;
            String marker = "filename*=";
            int at = indexOfIgnoreCase(disposition, marker);
            if (at >= 0) {
                value = disposition.substring(at + marker.length());
            } else {
                marker = "filename=";
                at = indexOfIgnoreCase(disposition, marker);
                if (at >= 0) {
                    value = disposition.substring(at + marker.length());
                }
            }
            if (value != null) {
                String name = value.split(";", 2)[0].trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1).trim();
                }
                if (name.toLowerCase(Locale.ROOT).startsWith("utf-8''")) {
                    name = name.substring("utf-8''".length());
                }
                if (!name.isEmpty()) {
                    return percentDecodeOrRaw(name);
                }
            }
        }
        return fallback;
    }

    private int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /** 解码失败就用原值（原版 {@code unquote} 遇到坏转义会抛异常，那里同样退回原文）。 */
    private String percentDecodeOrRaw(String value) {
        try {
            return NodeCanvasFiles.percentDecode(value);
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    /** 与原版 {@code raw_body()} 同一句话：{@code 上传内容太大（12.3MB），上限 500MB}。 */
    private String tooLargeMessage(int length) {
        return String.format(Locale.ROOT, "上传内容太大（%.1fMB），上限 %.0fMB",
                length / 1048576.0, NodeAssetService.MAX_ASSET_BYTES / 1048576.0);
    }
}

package com.lumenforge.controller.video;

import com.lumenforge.common.util.Texts;
import com.lumenforge.service.video.VideoGenerationService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;

/**
 * 视频生成接口，路径与响应字段对齐 Python 版 {@code app/api/video_api.py}。
 *
 * <p>输入图上传是<b>裸二进制</b>（不是 multipart）：前端直接 {@code POST} 图片字节，
 * 文件名走 {@code x-filename} 头。为了兼容工具链（curl -F、Postman）也接受 multipart，
 * 取第一个文件字段。
 */
@RestController
@RequestMapping("/api")
public class VideoGenerationController {

    /** 与 Python 版 {@code raw_body(limit=40MB)} 一致。 */
    private static final long MAX_UPLOAD_BYTES = 40L * 1024L * 1024L;

    private final VideoGenerationService service;

    public VideoGenerationController(VideoGenerationService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 模型

    @GetMapping("/video-models")
    public List<Map<String, Object>> listModels() {
        return service.listModels();
    }

    @PostMapping("/video-models")
    public Map<String, Object> createModel(@RequestBody(required = false) Map<String, Object> body) {
        return service.createModel(body);
    }

    @PatchMapping("/video-models/{id}")
    public Map<String, Object> updateModel(@PathVariable("id") String id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return service.updateModel(id, body);
    }

    @DeleteMapping("/video-models/{id}")
    public Map<String, Object> deleteModel(@PathVariable("id") String id) {
        return service.deleteModel(id);
    }

    // ---------------------------------------------------------------- 输入图库

    @GetMapping("/video-inputs")
    public List<Map<String, Object>> listInputs() {
        return service.listInputs();
    }

    @PostMapping("/video-inputs")
    public Map<String, Object> uploadInput(HttpServletRequest request,
                                           @RequestHeader(value = "X-Filename", required = false) String headerName,
                                           @RequestHeader(value = "Content-Disposition", required = false) String disposition)
            throws IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")
                && request instanceof MultipartHttpServletRequest) {
            MultipartFile file = firstFile((MultipartHttpServletRequest) request);
            if (file != null) {
                String name = file.getOriginalFilename();
                if (name == null || name.trim().isEmpty()) {
                    name = firstNonEmpty(headerName, "input.png");
                }
                return service.uploadInput(file.getBytes(), name, file.getContentType());
            }
        }
        long declared = request.getContentLengthLong();
        if (declared > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("上传内容太大（" + fmtMb(declared) + "），上限 "
                    + fmtMb(MAX_UPLOAD_BYTES));
        }
        byte[] buffer = readBody(request.getInputStream());
        String originalName = firstNonEmpty(decodeFileName(headerName),
                fileNameFromDisposition(disposition), "input.png");
        return service.uploadInput(buffer, originalName, contentType);
    }

    @DeleteMapping("/video-inputs/{id}")
    public Map<String, Object> deleteInput(@PathVariable("id") String id) {
        return service.deleteInput(id);
    }

    // ---------------------------------------------------------------- 任务

    @GetMapping("/video-tasks")
    public List<Map<String, Object>> listTasks(@RequestParam(value = "limit", defaultValue = "120") int limit) {
        return service.listTasks(limit);
    }

    @PostMapping("/video-tasks")
    public Map<String, Object> createTask(@RequestBody(required = false) Map<String, Object> body) {
        return service.createTask(body);
    }

    @PostMapping("/video-tasks/{id}/retry")
    public Map<String, Object> retryTask(@PathVariable("id") String id) {
        return service.retryTask(id);
    }

    @PostMapping("/video-tasks/{id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable("id") String id) {
        return service.cancelTask(id);
    }

    @DeleteMapping("/video-tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable("id") String id) {
        return service.deleteTask(id);
    }

    /**
     * 把某张已生成的图片直接送去视频模块（结果图卡片上的「送去视频」按钮）。
     *
     * <p>返回的是新建的输入图记录，和 Python 版 {@code image_to_video_input} 一致。
     */
    @PostMapping("/image-tasks/{taskId}/to-video")
    public Map<String, Object> imageTaskToVideo(@PathVariable("taskId") String taskId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return service.imageTaskToVideo(taskId, body);
    }

    // ---------------------------------------------------------------- 小工具

    private MultipartFile firstFile(MultipartHttpServletRequest request) {
        for (String key : new String[]{"file", "image", "upload"}) {
            MultipartFile file = request.getFile(key);
            if (file != null && !file.isEmpty()) {
                return file;
            }
        }
        for (MultipartFile candidate : request.getFileMap().values()) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private byte[] readBody(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0L;
        int read;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("上传内容太大（" + fmtMb(total) + "），上限 "
                        + fmtMb(MAX_UPLOAD_BYTES));
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    /** 前端用 {@code encodeURIComponent} 传文件名，这里解回来。 */
    private String decodeFileName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException err) {
            return raw;
        }
    }

    /** 兜底：从 {@code Content-Disposition} 里取 {@code filename*} 或 {@code filename}。 */
    private String fileNameFromDisposition(String header) {
        if (header == null || header.trim().isEmpty()) {
            return "";
        }
        String value = header;
        int star = value.indexOf("filename*=");
        if (star >= 0) {
            String rest = value.substring(star + "filename*=".length()).trim();
            int semicolon = rest.indexOf(';');
            if (semicolon >= 0) {
                rest = rest.substring(0, semicolon);
            }
            rest = stripQuotes(rest.trim());
            if (rest.toLowerCase().startsWith("utf-8''")) {
                rest = rest.substring("utf-8''".length());
            }
            String decoded = decodeFileName(rest);
            if (!decoded.isEmpty()) {
                return decoded;
            }
        }
        int plain = value.indexOf("filename=");
        if (plain >= 0) {
            String rest = value.substring(plain + "filename=".length()).trim();
            int semicolon = rest.indexOf(';');
            if (semicolon >= 0) {
                rest = rest.substring(0, semicolon);
            }
            return decodeFileName(stripQuotes(rest.trim()));
        }
        return "";
    }

    private String stripQuotes(String value) {
        String text = value;
        if (text.startsWith("\"")) {
            text = text.substring(1);
        }
        if (text.endsWith("\"")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !Texts.trim(value).isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String fmtMb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }
}

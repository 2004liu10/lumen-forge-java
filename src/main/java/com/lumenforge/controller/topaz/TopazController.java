package com.lumenforge.controller.topaz;

import com.lumenforge.service.topaz.TopazService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Topaz 视频增强 HTTP 模块。
 *
 * <p>响应形状与 Python 版 {@code app/api/topaz_api.py} 逐字段一致。十条路由里九条是普通的
 * JSON 收发，只有 {@code GET /api/topaz/local-file} 要自己实现 HTTP Range：
 * 前端播放器拖进度条靠它取片段，Spring 默认的静态资源响应不支持 206，所以这里手写
 * {@code 200 / 206 / 416} 三种分支，语义照抄 Python 版。
 */
@RestController
@RequestMapping("/api/topaz")
public class TopazController {

    /** Range 头解析：只认 {@code bytes=} 开头、形如 {@code bytes=start-end} 的写法。 */
    private static final Pattern RANGE_RE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** 流式分块大小：与 Python 版的 65536 一致。 */
    private static final int CHUNK_SIZE = 65536;

    private final TopazService service;

    public TopazController(TopazService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public Map<String, Object> candidates() {
        return service.candidates();
    }

    @PostMapping("/enhance")
    public Map<String, Object> enhance(@RequestBody(required = false) Map<String, Object> body) {
        return service.enhance(body);
    }

    @PostMapping("/enhance-batch")
    public Map<String, Object> enhanceBatch(@RequestBody(required = false) Map<String, Object> body) {
        return service.enhanceBatch(body);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody(required = false) Map<String, Object> body) {
        return service.cancel(body);
    }

    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody(required = false) Map<String, Object> body) {
        return service.restore(body);
    }

    @PostMapping("/clear-failed")
    public Map<String, Object> clearFailed() {
        return service.clearFailed();
    }

    @PostMapping("/config")
    public Map<String, Object> config(@RequestBody(required = false) Map<String, Object> body) {
        return service.config(body);
    }

    @PostMapping("/look")
    public Map<String, Object> look(@RequestBody(required = false) Map<String, Object> body) {
        return service.look(body);
    }

    @PostMapping("/local")
    public Map<String, Object> local(@RequestBody(required = false) Map<String, Object> body) {
        return service.local(body);
    }

    /**
     * 本地视频预览：带 Range 支持，供前端 {@code <video>} 拖进度条。
     *
     * <p>路径校验交给 {@link TopazService#resolveLocalVideo(String)}：不是绝对路径、不是视频
     * 扩展名、不是真实文件、或落在允许根目录之外，一律回 404 空体（不泄露「这个路径存在但你不许看」）。
     */
    @GetMapping("/local-file")
    public void localFile(@RequestParam(value = "path", required = false) String path,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        Path file = service.resolveLocalVideo(path);
        if (file == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long size = java.nio.file.Files.size(file);
        String mime = service.videoMime(path);

        long start = 0L;
        long end = size - 1L;
        boolean partial = false;

        String rangeHeader = request.getHeader("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            Matcher matcher = RANGE_RE.matcher(rangeHeader);
            if (matcher.find()) {
                partial = true;
                String startText = matcher.group(1);
                String endText = matcher.group(2);
                if (!startText.isEmpty()) {
                    start = parseLong(startText, start);
                }
                if (!endText.isEmpty()) {
                    end = parseLong(endText, end);
                }
            }
        }

        if (start < 0L) {
            start = 0L;
        }
        if (end >= size) {
            end = size - 1L;
        }

        if (size == 0L) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Length", "0");
            return;
        }
        if (start > end) {
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }

        response.setHeader("Content-Type", mime);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Length", String.valueOf(end - start + 1L));
        response.setStatus(partial ? 206 : HttpServletResponse.SC_OK);
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }

        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        try {
            raf.seek(start);
            long remaining = end - start + 1L;
            byte[] buffer = new byte[CHUNK_SIZE];
            while (remaining > 0L) {
                int want = (int) Math.min((long) CHUNK_SIZE, remaining);
                int read = raf.read(buffer, 0, want);
                if (read <= 0) {
                    break;
                }
                response.getOutputStream().write(buffer, 0, read);
                remaining -= read;
            }
            response.getOutputStream().flush();
        } finally {
            raf.close();
        }
    }

    private long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

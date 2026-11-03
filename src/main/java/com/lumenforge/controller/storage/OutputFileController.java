package com.lumenforge.controller.storage;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 本地文件访问模块。
 *
 * <p>Python 版对 8 个分类都注册了 {@code /files/{prefix}/{rel:path}}（{@code rel} 是**可含斜杠**的
 * 多段路径，例如 {@code generated/refs/abc.png}），外加一条 {@code /files/{rel:path}} 兜底。
 * 这里用单条 {@code /files/**} 手写前缀解析，既避免 Spring 对多段路径变量的匹配歧义，
 * 又能保证路径必须落在对应分类目录之内，防止被路径穿越读走任意文件。</p>
 */
@RestController
public class OutputFileController {
    /** 与 Python 的 FILE_PREFIXES 完全一致；{@code generated/refs} 必须排在 {@code generated} 前面。 */
    private static final List<String> FILE_PREFIXES = Arrays.asList(
            "generated/refs", "uploads", "outputs", "topaz", "generated", "videos", "video-inputs", "materials");

    private final Path storageRoot;

    public OutputFileController(@org.springframework.beans.factory.annotation.Value("${app.storage.root:./data}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 媒体访问：{@code /files/generated/refs/x.png}、{@code /files/uploads/x.png}、{@code /files/x.png}。
     *
     * <p>先按白名单里最长前缀匹配（{@code generated/refs} 优先于 {@code generated}），
     * 剩下的部分作为该目录内的相对路径；没有命中任何前缀时退回数据根目录（Python 的兜底路由）。</p>
     */
    @GetMapping("/files/**")
    public ResponseEntity<Resource> file(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.indexOf("/files/");
        if (index < 0) return ResponseEntity.notFound().build();
        String rel = uri.substring(index + "/files/".length());
        if (rel.isEmpty()) return ResponseEntity.notFound().build();
        // 反斜杠统一成正斜杠，并剥掉可能出现的查询串残留
        rel = rel.replace('\\', '/');
        for (String prefix : FILE_PREFIXES) {
            if (rel.equals(prefix) || rel.startsWith(prefix + "/")) {
                String rest = rel.substring(prefix.length());
                while (rest.startsWith("/")) rest = rest.substring(1);
                if (rest.isEmpty()) return ResponseEntity.notFound().build();
                // 注意：这一步解析的是**目录**，不能用 resolveInside（它要求目标必须是普通文件，
                // 对目录恒为 false，会把所有 /files/** 请求判成 404）。目录只需校验落在根内。
                Path dir = resolveDir(storageRoot, prefix);
                if (dir == null) return ResponseEntity.notFound().build();
                Path target = resolveInside(dir, rest);
                return target == null ? ResponseEntity.notFound().build() : respond(target);
            }
        }
        Path fallback = resolveInside(storageRoot, rel);
        return fallback == null ? ResponseEntity.notFound().build() : respond(fallback);
    }

    /** 把 {@code rel} 解析成 {@code base} 之内的**目录**；越界或不是目录则返回 null。 */
    private Path resolveDir(Path base, String rel) {
        if (rel == null || rel.trim().isEmpty()) return null;
        String normalized = rel.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return null;
        Path target = base.resolve(normalized).normalize();
        if (!target.startsWith(base) || !Files.isDirectory(target)) return null;
        return target;
    }

    /** 把 {@code rel} 解析到 {@code base} 之内；越界或不是普通文件则返回 null。 */
    private Path resolveInside(Path base, String rel) {
        if (rel == null || rel.trim().isEmpty()) return null;
        String normalized = rel.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return null;
        Path target = base.resolve(normalized).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) return null;
        return target;
    }

    private ResponseEntity<Resource> respond(Path file) {
        String contentType;
        try {
            contentType = Files.probeContentType(file);
        } catch (Exception exception) {
            contentType = null;
        }
        MediaType mediaType = contentType == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        long size;
        try {
            size = Files.size(file);
        } catch (Exception exception) {
            size = -1L;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(mediaType);
        if (size >= 0) builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
        return builder.body(new FileSystemResource(file));
    }
}

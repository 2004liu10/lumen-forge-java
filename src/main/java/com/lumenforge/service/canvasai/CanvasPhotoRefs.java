package com.lumenforge.service.canvasai;

import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.image.ImageReference;
import com.lumenforge.mapper.image.ImageReferenceMapper;
import com.lumenforge.service.storage.FileStorageService;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「参考图库里的 id」收成能直接发给模型 / 挂到角色上的图。
 *
 * <p>对应 Python 版 {@code storyboard.collect_photo_refs}，返回结构一致：
 * {@code [{ref: <参考图视图>, dataUri: "data:image/png;base64,…"}]}。
 *
 * <p>为什么不复用「小剧场」里的同名逻辑：那边是 {@code StoryboardService} 的私有方法，
 * 跨包调不到；而它是画布和小剧场两处都要用的公共能力，复制一份私有实现会让两边的
 * data URI 口径（MIME 兜底、链接图跳过）慢慢走偏，所以这里单独落一个组件。
 */
@Component
public class CanvasPhotoRefs {

    /** 参考图扩展名 → MIME。与「小剧场」那套保持同一份表，避免两处识别出不同 MIME。 */
    private static final Map<String, String> REF_MIME_TYPES = new LinkedHashMap<String, String>();

    static {
        REF_MIME_TYPES.put(".png", "image/png");
        REF_MIME_TYPES.put(".jpg", "image/jpeg");
        REF_MIME_TYPES.put(".jpeg", "image/jpeg");
        REF_MIME_TYPES.put(".webp", "image/webp");
        REF_MIME_TYPES.put(".gif", "image/gif");
        REF_MIME_TYPES.put(".bmp", "image/bmp");
    }

    private final ImageReferenceMapper imageRefs;
    private final FileStorageService files;

    public CanvasPhotoRefs(ImageReferenceMapper imageRefs, FileStorageService files) {
        this.imageRefs = imageRefs;
        this.files = files;
    }

    /**
     * 收集人物参考图。
     *
     * <p>外网链接图、文件已丢失的图都直接跳过 —— 它们没法塞进 {@code messages} 的 image_url，
     * 与其发一张空图让模型报错，不如少一张。返回的条数可能少于传入的 id 数，调用方按实际条数算。
     */
    public List<Map<String, Object>> collect(Object photoIds, int maxCount) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(photoIds instanceof List)) {
            return out;
        }
        List<?> ids = (List<?>) photoIds;
        int limit = Math.min(ids.size(), maxCount);
        for (int i = 0; i < limit; i++) {
            String refId = Texts.trim(ids.get(i));
            if (refId.isEmpty()) {
                continue;
            }
            ImageReference ref = imageRefs.findById(refId).orElse(null);
            if (ref == null) {
                continue;
            }
            String dataUri = dataUriOf(ref);
            if (dataUri.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("ref", view(ref));
            entry.put("dataUri", dataUri);
            out.add(entry);
        }
        return out;
    }

    /** 把一张参考图读成 data URI；文件不在了返回空串（不抛错，调用方跳过即可）。 */
    private String dataUriOf(ImageReference ref) {
        try {
            if (ref.isLink()) {
                return ""; // 外网链接图没法直接塞进 messages，跳过
            }
            String fileId = ref.getFileId();
            if (fileId == null || fileId.trim().isEmpty()) {
                return "";
            }
            Path path = files.resolve(files.require(fileId));
            if (!Files.isRegularFile(path)) {
                return "";
            }
            String ext = FileStorageService.extensionOf(path.getFileName().toString());
            String mime = files.require(fileId).getMimeType();
            if (mime == null || mime.trim().isEmpty()) {
                mime = REF_MIME_TYPES.get(ext);
            }
            if (mime == null || mime.trim().isEmpty()) {
                mime = "image/png";
            }
            byte[] buffer = Files.readAllBytes(path);
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(buffer);
        } catch (IOException | RuntimeException err) {
            return "";
        }
    }

    /** 参考图库记录压成前端用的视图（字段与图片模块一致）。 */
    private Map<String, Object> view(ImageReference ref) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ref.getId());
        out.put("source", ref.getSource());
        out.put("originalName", ref.getOriginalName());
        out.put("createdAt", ref.getCreatedAt() == null ? null : ref.getCreatedAt().toString());
        if (ref.isLink()) {
            out.put("fileName", "");
            out.put("localPath", "");
            out.put("localUrl", ref.getRemoteUrl());
            out.put("mimeType", "");
            out.put("size", 0L);
            out.put("url", ref.getRemoteUrl());
            return out;
        }
        String fileId = ref.getFileId();
        if (fileId == null || fileId.trim().isEmpty()) {
            putMissing(out);
            return out;
        }
        com.lumenforge.entity.storage.StoredFile stored;
        try {
            stored = files.require(fileId);
        } catch (IllegalArgumentException err) {
            putMissing(out);
            return out;
        }
        Path path = files.resolve(stored);
        out.put("fileName", stored.getStorageName());
        out.put("localPath", path.toString());
        out.put("localUrl", files.localUrlFor(stored));
        out.put("mimeType", stored.getMimeType());
        out.put("size", stored.getSizeBytes());
        out.put("url", "");
        return out;
    }

    private static void putMissing(Map<String, Object> out) {
        out.put("fileName", "");
        out.put("localPath", "");
        out.put("localUrl", "");
        out.put("mimeType", "");
        out.put("size", 0L);
        out.put("url", "");
    }
}

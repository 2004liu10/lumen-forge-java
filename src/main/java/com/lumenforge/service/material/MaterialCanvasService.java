package com.lumenforge.service.material;

import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.entity.image.ImageReference;
import com.lumenforge.entity.material.MaterialSource;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.mapper.image.ImageReferenceMapper;
import com.lumenforge.service.canvas.CanvasService;
import com.lumenforge.service.storage.FileStorageService;
import com.lumenforge.service.topic.TopicService;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材「投送」业务：把素材挂到画布的视频参数上，或登记成图生图的参考图。
 *
 * <p>对应 Python 版 {@code app/api/materials_api.py} 的
 * {@code material_to_canvas()} / {@code material_to_image_ref()}。
 *
 * <p>投送出去的都是「共享引用」—— 画布只记路径，不复制文件；所以清理旧素材时
 * 必须走 {@code FileStorageService.unlinkIfUnused()}，它会在还有登记记录时保留文件。
 */
@Service
public class MaterialCanvasService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialCanvasService.class);

    /** 画布 asset 里标记「这份文件来自素材库」。 */
    private static final String ORIGIN_MATERIAL = "material";

    /** 话题名最长 12 字（前端话题标签的显示宽度）。 */
    private static final int TOPIC_MAX = 12;

    /** 素材来源描述最长 80 字。 */
    private static final int SOURCE_DESC_MAX = 80;

    private final CanvasService canvases;
    private final MaterialSourceService sources;
    private final MaterialDownloadService downloads;
    private final TopicService topics;
    private final FileStorageService files;
    private final ImageReferenceMapper refs;
    private final StoragePaths paths;

    public MaterialCanvasService(CanvasService canvases,
                                 MaterialSourceService sources,
                                 MaterialDownloadService downloads,
                                 TopicService topics,
                                 FileStorageService files,
                                 ImageReferenceMapper refs,
                                 StoragePaths paths) {
        this.canvases = canvases;
        this.sources = sources;
        this.downloads = downloads;
        this.topics = topics;
        this.files = files;
        this.refs = refs;
        this.paths = paths;
    }

    // ------------------------------------------------------------------ 送画布

    /**
     * 把素材（下载或本地文件）挂到画布的视频参数上，并顺手把带的抖音话题收进话题库。
     *
     * @return {@code {ok, canvasId, canvasName, field, topicsAdded, topicsExisted}}
     */
    public Map<String, Object> toCanvas(Map<String, Object> body) {
        String canvasId = Texts.str(body, "canvasId");
        if (canvasId.isEmpty()) {
            throw new IllegalArgumentException("请选择要添加到的画布");
        }
        Canvas canvas = canvases.require(canvasId);
        Map<String, Object> view = canvases.view(canvas);
        List<Map<String, Object>> fields = maps(view.get("fields"));
        Map<String, Object> field = null;
        String requestedKey = Texts.str(body, "fieldKey");
        if (!requestedKey.isEmpty()) {
            for (Map<String, Object> candidate : fields) {
                if (requestedKey.equals(fieldKey(candidate))) {
                    field = candidate;
                    break;
                }
            }
        } else {
            for (Map<String, Object> candidate : fields) {
                if ("VIDEO".equals(Texts.str(candidate, "fieldType").toUpperCase())) {
                    field = candidate;
                    break;
                }
            }
        }
        if (field == null) {
            throw new IllegalArgumentException("画布「" + canvas.getName()
                    + "」还没有视频输入参数，请先在该画布点「读取参数」");
        }
        if (!"VIDEO".equals(Texts.str(field, "fieldType").toUpperCase())) {
            throw new IllegalArgumentException("选中的参数不是视频类型，请选「加载参考视频」这类参数");
        }

        Map<String, Object> file = resolveFile(body, "video");
        String localPath = Texts.str(file, "localPath");
        String fileName = Texts.str(file, "fileName");
        if (fileName.isEmpty()) {
            fileName = Paths.get(localPath).getFileName().toString();
        }
        String ext = FileStorageService.extensionOf(fileName);
        String mime = MaterialConstants.mimeOfExt(ext, "video/mp4");
        long size = Texts.asLong(file.get("size"), 0L);

        String targetKey = fieldKey(field);
        Map<String, Object> assets = objectMap(view.get("assets"));
        Map<String, Object> prev = objectMap(assets.get(targetKey));

        // 共享引用：只记路径，不复制文件
        Map<String, Object> asset = new LinkedHashMap<String, Object>();
        asset.put("localPath", localPath);
        asset.put("originalName", Texts.str(file, "fileName").isEmpty()
                ? Paths.get(localPath).getFileName().toString() : Texts.str(file, "fileName"));
        asset.put("mimeType", mime);
        asset.put("size", size);
        asset.put("shared", true);
        asset.put("origin", ORIGIN_MATERIAL);
        asset.put("localUrl", materialUrl(localPath));
        asset.put("uploadedAt", System.currentTimeMillis());
        asset.put("fromMaterial", true);
        asset.put("topics", topicNames(body.get("topics")));
        asset.put("sourceDesc", Texts.truncate(Texts.str(body, "sourceDesc"), SOURCE_DESC_MAX));
        assets.put(targetKey, asset);

        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("assets", assets);
        canvases.update(canvas.getId(), patch);

        List<String> incoming = castStrings(asset.get("topics"));
        // 与 Python 一致：回给前端的是**名字列表**（前端按 `(res.topicsAdded || []).length` 取数量），
        // 一条都没收进来时退回 0，跟 `add_topics(...).get("added") or 0` 等价。
        Object added = 0;
        Object existed = 0;
        if (!incoming.isEmpty()) {
            Map<String, Object> topicBody = new LinkedHashMap<String, Object>();
            topicBody.put("names", incoming);
            topicBody.put("source", "douyin");
            String desc = Texts.str(asset, "sourceDesc");
            topicBody.put("sourceDesc", desc.isEmpty() ? Texts.str(file, "fileName") : desc);
            Map<String, Object> result = topics.add(topicBody);
            added = nonEmptyOrZero(result == null ? null : result.get("added"));
            existed = nonEmptyOrZero(result == null ? null : result.get("existed"));
        }

        // 目标位置原来的素材如果换了文件，且没人再引用，就顺手清掉
        String prevPath = Texts.str(prev, "localPath");
        if (!prevPath.isEmpty() && !prevPath.equals(localPath)) {
            files.unlinkIfUnused(Paths.get(prevPath));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("canvasId", canvas.getId());
        out.put("canvasName", canvas.getName());
        out.put("field", fieldLabel(field));
        out.put("topicsAdded", added);
        out.put("topicsExisted", existed);
        return out;
    }

    // ------------------------------------------------------------------ 转参考图

    /**
     * 把素材登记成图生图参考图（不复制文件，直接引用素材库里的那份）。
     *
     * @return 参考图快照，键序与图片模块的 refView 一致
     */
    public Map<String, Object> toImageRef(Map<String, Object> body) {
        Map<String, Object> file = resolveFile(body, "image");
        String localPath = Texts.str(file, "localPath");
        Path path = absolutePath(localPath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("素材文件已不存在，无法用作参考图");
        }
        String name = Texts.str(file, "fileName");
        if (name.isEmpty()) {
            name = path.getFileName().toString();
        }
        String mime = Texts.str(file, "mimeType");
        if (mime.isEmpty()) {
            mime = MaterialConstants.mimeOfExt(FileStorageService.extensionOf(name), "");
        }
        // 不复制文件：素材本来就住在 materials/ 下，登记成 materials 类别才能让
        // localUrlFor() 拼出 /files/materials/<name>（拼成 refs 会指向不存在的地址）
        StoredFile stored = files.register(path, "materials", name, mime);
        ImageReference ref = new ImageReference(stored.getId(), null, name, ORIGIN_MATERIAL);
        ImageReference saved = refs.save(ref);
        return refView(saved);
    }

    // ------------------------------------------------------------------ 内部

    /** 本地文件直接登记，远端地址则先下载落盘。 */
    private Map<String, Object> resolveFile(Map<String, Object> body, String kind) {
        String localFile = Texts.str(body, "localFile");
        if (!localFile.isEmpty()) {
            return downloads.importLocal(localFile, kind);
        }
        Map<String, String> headers = requestHeaders(body);
        return downloads.download(Texts.str(body, "url"), kind, Texts.str(body, "referer"),
                headers, Texts.asBool(body.get("unique"), false));
    }

    /** 指定了素材分类就用它的请求头（很多接口要 Referer 或 Key 才给资源）。 */
    private Map<String, String> requestHeaders(Map<String, Object> body) {
        String sourceId = Texts.str(body, "sourceId");
        if (sourceId.isEmpty()) {
            return new LinkedHashMap<String, String>();
        }
        MaterialSource source = sources.require(sourceId);
        return sources.headers(source);
    }

    /** 素材在 {@code materials} 目录就给素材的访问前缀，否则按 uploads 走。 */
    private String materialUrl(String localPath) {
        Path file = Paths.get(localPath).toAbsolutePath().normalize();
        Path materialsDir = paths.materials().toAbsolutePath().normalize();
        String name = file.getFileName().toString();
        return file.startsWith(materialsDir) ? "/files/materials/" + name : "/files/uploads/" + name;
    }

    /** 画布字段的展示名：{@code 描述=值} 形式，与 Python {@code field_label()} 一致。 */
    private String fieldLabel(Map<String, Object> field) {
        String desc = Texts.str(field, "description");
        if (desc.isEmpty()) desc = Texts.str(field, "nodeName");
        if (desc.isEmpty()) desc = Texts.str(field, "fieldName");
        return desc + "=";
    }

    private String fieldKey(Map<String, Object> field) {
        return Texts.str(field, "nodeId") + "::" + Texts.str(field, "fieldName");
    }

    /** 话题名统一去前导 # 与空白、截 12 字。 */
    private List<String> topicNames(Object raw) {
        List<String> out = new ArrayList<String>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (String item : castStrings(raw)) {
            String name = item;
            while (name.startsWith("#")) {
                name = name.substring(1);
            }
            name = name.trim();
            if (!name.isEmpty()) {
                out.add(Texts.truncate(name, TOPIC_MAX));
            }
        }
        return out;
    }

    private Map<String, Object> refView(ImageReference ref) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ref.getId());
        out.put("source", ref.getSource());
        out.put("originalName", ref.getOriginalName());
        out.put("createdAt", ref.getCreatedAt() == null ? "" : ref.getCreatedAt().toString());
        StoredFile stored = null;
        if (ref.getFileId() != null && !ref.getFileId().trim().isEmpty()) {
            try {
                stored = files.require(ref.getFileId());
            } catch (IllegalArgumentException ignored) {
                stored = null;
            }
        }
        if (stored == null) {
            out.put("fileName", "");
            out.put("localPath", "");
            out.put("localUrl", "");
            out.put("mimeType", "");
            out.put("size", 0L);
            out.put("url", "");
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

    private Path absolutePath(String raw) {
        return Paths.get(raw == null ? "" : raw).toAbsolutePath().normalize();
    }

    /** 话题接口回的是名字列表；空列表时按 Python 的 `or 0` 退回数字 0。 */
    private Object nonEmptyOrZero(Object raw) {
        if (raw instanceof List && !((List<?>) raw).isEmpty()) {
            return raw;
        }
        return 0;
    }

    private long epochMs(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object raw) {
        if (raw instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) raw);
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object item : (List<Object>) raw) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> castStrings(Object raw) {
        List<String> out = new ArrayList<String>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object item : (List<Object>) raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}

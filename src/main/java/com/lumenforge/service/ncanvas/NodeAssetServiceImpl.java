package com.lumenforge.service.ncanvas;

import com.lumenforge.common.util.Ids;
import com.lumenforge.entity.image.ImageReference;
import com.lumenforge.entity.ncanvas.NodeCanvasNotFoundException;
import com.lumenforge.entity.ncanvas.NodeCanvasStorageException;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.mapper.image.ImageReferenceMapper;
import com.lumenforge.mapper.ncanvas.JsonRowStore;
import com.lumenforge.mapper.ncanvas.NodeAssetStore;
import com.lumenforge.mapper.ncanvas.NodeCanvasStore;
import com.lumenforge.service.storage.FileStorageService;
import com.lumenforge.storage.StoragePaths;
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
 * 本地素材库业务实现。
 *
 * <p>逐函数对照 Python 版 {@code app/node_canvas.py} 的 {@code register_upload / register_existing /
 * asset_to_image_ref / asset_lists / patch_asset / trash_asset / restore_asset / purge_asset /
 * clear_trash}。
 *
 * <p>素材文件<b>不走</b> {@link FileStorageService#store}：那边的存储名是
 * {@code up_<id><ext>}，还会在 {@code stored_files} 里留一行登记，而
 * {@code unlinkIfUnused} 见到登记行就拒绝删盘 —— 回收站「永久删除」会永远删不掉。
 * 原版就是直接把字节写到 {@code uploads/<assetId><ext>}，这里照抄。
 */
@Service
public class NodeAssetServiceImpl implements NodeAssetService {

    private final NodeAssetStore store;
    private final NodeCanvasStore canvases;
    private final StoragePaths paths;
    private final FileStorageService files;
    private final ImageReferenceMapper refs;

    public NodeAssetServiceImpl(NodeAssetStore store, NodeCanvasStore canvases, StoragePaths paths,
                                FileStorageService files, ImageReferenceMapper refs) {
        this.store = store;
        this.canvases = canvases;
        this.paths = paths;
        this.files = files;
        this.refs = refs;
    }

    @Override
    public Map<String, Object> registerUpload(final byte[] buffer, final String originalName,
                                              final String mimeType) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("没有收到文件内容");
        }
        final String name = originalName == null || originalName.isEmpty() ? "素材" : originalName;
        final String ext = NodeCanvasFiles.suffix(name);
        final String kind = NodeCanvasSanitizer.extKind(ext);
        if (kind.isEmpty()) {
            throw new IllegalArgumentException(
                    "只支持图片（png/jpg/webp/gif…）、视频（mp4/mov/webm…）、音频（mp3/wav/m4a…）");
        }
        final String assetId = Ids.nextId("na");
        final String storedName = assetId + ext;
        final Path dir = paths.uploads();
        final Path target = dir.resolve(storedName).normalize();
        try {
            Files.createDirectories(dir);
            Files.write(target, buffer);
        } catch (IOException err) {
            throw new NodeCanvasStorageException("写入素材目录失败：" + err.getMessage(), err);
        }
        final String mime = mimeType == null || mimeType.trim().isEmpty()
                ? NodeCanvasSanitizer.KIND_MIME.get(kind) : mimeType.trim();

        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> asset = new LinkedHashMap<String, Object>();
                asset.put("id", assetId);
                asset.put("kind", kind);
                asset.put("fileName", storedName);
                asset.put("originalName", name);
                asset.put("localPath", target.toString());
                asset.put("localUrl", "/files/uploads/" + storedName);
                asset.put("mimeType", mime);
                asset.put("size", (long) buffer.length);
                asset.put("favorite", Boolean.FALSE);
                asset.put("deletedAt", null);
                asset.put("createdAt", Ids.nowMs());
                rows.add(asset);
                dirty.mark();
                return asset;
            }
        });
    }

    @Override
    public Map<String, Object> registerExisting(final Map<String, Object> body) {
        Map<String, Object> cleanBody = body == null ? new LinkedHashMap<String, Object>() : body;
        // 生成结果节点只有 /files/... 的 URL，没有 localPath：按 URL 反推它真实所在的目录
        Path fromUrl = NodeCanvasFiles.pathFromFilesUrl(
                cleanBody.get("localUrl") == null ? "" : String.valueOf(cleanBody.get("localUrl")), paths);
        Object localPath = cleanBody.get("localPath");
        String raw = localPath == null || String.valueOf(localPath).isEmpty()
                ? (fromUrl == null ? "" : fromUrl.toString())
                : String.valueOf(localPath);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("只允许登记本程序管理的目录里的文件");
        }
        Path absPath;
        try {
            absPath = Paths.get(raw).toAbsolutePath().normalize();
        } catch (RuntimeException err) {
            throw new IllegalArgumentException("只允许登记本程序管理的目录里的文件");
        }
        if (!NodeCanvasFiles.isAllowedRegisterPath(absPath, paths)) {
            throw new IllegalArgumentException("只允许登记本程序管理的目录里的文件");
        }
        if (!Files.exists(absPath)) {
            throw new IllegalArgumentException("文件不存在，无法登记");
        }
        if (!Files.isRegularFile(absPath)) {
            throw new IllegalArgumentException("目标的不是一个文件");
        }

        final Path target = absPath;
        final String key = NodeCanvasFiles.pathKey(target.toString());
        final String ext = NodeCanvasFiles.suffix(target.getFileName().toString());
        // 扩展名认得出就用它，认不出才信请求体里的 kind；两者都没有时按图片算（原版的 `or "image"`）
        String declaredKind = cleanBody.get("kind") == null ? "" : String.valueOf(cleanBody.get("kind")).trim();
        final String kind = NodeCanvasSanitizer.extKind(ext).isEmpty()
                ? (declaredKind.isEmpty() ? "image" : declaredKind) : NodeCanvasSanitizer.extKind(ext);
        if (!NodeCanvasSanitizer.KIND_EXTS.containsKey(kind)) {
            throw new IllegalArgumentException("无法识别的文件类型");
        }
        final String fileName = target.getFileName().toString();
        final String displayName = NodeCanvasSanitizer.text(cleanBody.get("fileName"), 300).isEmpty()
                ? fileName : NodeCanvasSanitizer.text(cleanBody.get("fileName"), 300);
        final long size;
        try {
            size = Files.size(target);
        } catch (IOException err) {
            throw new NodeCanvasStorageException("无法读取文件大小：" + err.getMessage(), err);
        }
        final String localUrl = NodeCanvasFiles.filesUrlFor(target, paths);

        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                // 同一份文件只登记一次：路径一致就直接返回原来那条
                for (Map<String, Object> existing : rows) {
                    Object value = existing.get("localPath");
                    String existingKey = NodeCanvasFiles.pathKey(
                            value == null ? "" : String.valueOf(value));
                    if (!existingKey.isEmpty() && existingKey.equals(key)) {
                        return existing;
                    }
                }
                Map<String, Object> asset = new LinkedHashMap<String, Object>();
                asset.put("id", Ids.nextId("na"));
                asset.put("kind", kind);
                asset.put("fileName", fileName);
                asset.put("originalName", displayName);
                asset.put("localPath", target.toString());
                // 按「它实际在哪个媒体目录里」拼 URL，改过存储位置后也能正常访问
                asset.put("localUrl", localUrl);
                asset.put("mimeType", NodeCanvasSanitizer.KIND_MIME.get(kind));
                asset.put("size", size);
                asset.put("favorite", Boolean.FALSE);
                asset.put("deletedAt", null);
                asset.put("shared", Boolean.TRUE);
                asset.put("createdAt", Ids.nowMs());
                rows.add(asset);
                dirty.mark();
                return asset;
            }
        });
    }

    @Override
    public Map<String, Object> toImageRef(final String assetId) {
        Map<String, Object> asset = requireAsset(assetId);
        Object kind = asset.get("kind");
        if (kind == null || !"image".equals(String.valueOf(kind))) {
            throw new IllegalArgumentException("只有图片能当人物参考图");
        }
        String localPath = asset.get("localPath") == null ? "" : String.valueOf(asset.get("localPath"));
        String originalName = asset.get("originalName") == null ? "" : String.valueOf(asset.get("originalName"));
        String mimeType = asset.get("mimeType") == null ? "" : String.valueOf(asset.get("mimeType"));
        long size = com.lumenforge.entity.ncanvas.NodeCanvasGraph.longOf(asset.get("size"));
        String assetUrl = asset.get("localUrl") == null ? "" : String.valueOf(asset.get("localUrl"));

        Path path = null;
        if (!localPath.isEmpty()) {
            try {
                path = Paths.get(localPath).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                path = null;
            }
        }
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("素材文件已不存在，无法用作参考图");
        }

        // 同一份素材反复当参考图很常见：已经登记过就返回原来那条，别每次点都往图库堆
        String key = NodeCanvasFiles.pathKey(path.toString());
        for (ImageReference ref : refs.findAllByOrderByCreatedAtDesc()) {
            if (ref.isLink()) {
                continue;
            }
            String existingKey = pathKeyOfRef(ref);
            if (!existingKey.isEmpty() && existingKey.equals(key)) {
                return refView(ref, asset, path);
            }
        }

        String category = categoryOf(path);
        StoredFile stored;
        try {
            stored = files.register(path, category, originalName.isEmpty() ? path.getFileName().toString()
                    : originalName, mimeType);
        } catch (IllegalArgumentException err) {
            throw new IllegalArgumentException(err.getMessage());
        }
        ImageReference ref = new ImageReference(stored.getId(), null,
                originalName.isEmpty() ? stored.getStorageName() : originalName, "material");
        ImageReference saved = refs.save(ref);
        return refView(saved, asset, path);
    }

    @Override
    public Map<String, Object> assetLists() {
        List<Map<String, Object>> rows = store.snapshot();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> trash = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> asset : rows) {
            if (asset.get("deletedAt") == null) {
                items.add(asset);
            } else {
                trash.add(asset);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        out.put("trash", trash);
        return out;
    }

    @Override
    public List<Map<String, Object>> snapshots() {
        List<Map<String, Object>> rows = store.snapshot();
        // 回收站里的也一起下发（前端按 deletedAt 分两栏），所以不能走 assetLists()
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (Map<String, Object> asset : rows) {
            out.add(new LinkedHashMap<String, Object>(asset));
        }
        return out;
    }

    @Override
    public Map<String, Object> patchAsset(final String assetId, final Map<String, Object> body) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> asset = requireIn(rows, assetId);
                Map<String, Object> cleanBody = body == null ? new LinkedHashMap<String, Object>() : body;
                if (cleanBody.get("favorite") != null) {
                    asset.put("favorite", NodeCanvasSanitizer.truthy(cleanBody.get("favorite")));
                }
                if (cleanBody.get("name") != null) {
                    String name = NodeCanvasSanitizer.text(cleanBody.get("name"), 300).trim();
                    if (!name.isEmpty()) {
                        asset.put("originalName", name);
                    }
                }
                dirty.mark();
                return asset;
            }
        });
    }

    @Override
    public Map<String, Object> trashAsset(final String assetId) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> asset = requireIn(rows, assetId);
                // 删除 = 先进回收站，文件不动；真要删在回收站里点「永久删除」
                asset.put("deletedAt", Ids.nowMs());
                dirty.mark();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                return out;
            }
        });
    }

    @Override
    public Map<String, Object> restoreAsset(final String assetId) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> asset = requireIn(rows, assetId);
                asset.put("deletedAt", null);
                dirty.mark();
                return asset;
            }
        });
    }

    @Override
    public Map<String, Object> purgeAsset(final String assetId) {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                Map<String, Object> asset = requireIn(rows, assetId);
                Object localPath = asset.get("localPath");
                NodeAssetStore.removeByIdentity(rows, asset);
                // 先摘记录再删：节点画布里如果还引用着这份文件，就留着
                unlinkIfUnused(localPath == null ? "" : String.valueOf(localPath));
                dirty.mark();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                return out;
            }
        });
    }

    @Override
    public Map<String, Object> clearTrash() {
        return store.mutate(new JsonRowStore.RowMutation<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(List<Map<String, Object>> rows, JsonRowStore.Dirty dirty) {
                List<Map<String, Object>> trashed = new ArrayList<Map<String, Object>>();
                List<Map<String, Object>> keep = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> asset : rows) {
                    if (asset.get("deletedAt") == null) {
                        keep.add(asset);
                    } else {
                        trashed.add(asset);
                    }
                }
                rows.clear();
                rows.addAll(keep);
                int removed = 0;
                for (Map<String, Object> asset : trashed) {
                    Object localPath = asset.get("localPath");
                    if (unlinkIfUnused(localPath == null ? "" : String.valueOf(localPath))) {
                        removed++;
                    }
                }
                dirty.mark();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                out.put("count", trashed.size());
                out.put("removed", removed);
                return out;
            }
        });
    }

    // ------------------------------------------------------------ 内部

    @Override
    public Map<String, Object> requireAsset(String assetId) {
        Map<String, Object> asset = NodeAssetStore.find(store.snapshot(), assetId);
        if (asset == null) {
            throw new NodeCanvasNotFoundException("素材不存在");
        }
        return asset;
    }

    private Map<String, Object> requireIn(List<Map<String, Object>> rows, String assetId) {
        Map<String, Object> asset = NodeAssetStore.find(rows, assetId);
        if (asset == null) {
            throw new NodeCanvasNotFoundException("素材不存在");
        }
        return asset;
    }

    /**
     * 没人引用时才删盘。
     *
     * <p>对应原版 {@code shared_files.unlink_if_unused}：画布节点的 {@code localPath} 还指着它
     * 就留着；另外 {@code stored_files} 里有登记行（说明被图库当参考图用了）也留着 ——
     * 这一条由 {@link FileStorageService#unlinkIfUnused} 兜住。
     *
     * @return 是否真的删掉了文件
     */
    private boolean unlinkIfUnused(String localPath) {
        if (localPath == null || localPath.trim().isEmpty()) {
            return false;
        }
        Path path;
        try {
            path = Paths.get(localPath.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!Files.exists(path)) {
            return false;
        }
        String key = NodeCanvasFiles.pathKey(path.toString());
        for (Map<String, Object> graph : canvases.snapshot()) {
            Object nodes = graph.get("nodes");
            if (!(nodes instanceof List)) {
                continue;
            }
            for (Object item : (List<?>) nodes) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Object nodePath = ((Map<?, ?>) item).get("localPath");
                if (nodePath == null) {
                    continue;
                }
                if (key.equals(NodeCanvasFiles.pathKey(String.valueOf(nodePath)))) {
                    return false;
                }
            }
        }
        files.unlinkIfUnused(path);
        return !Files.exists(path);
    }

    /** 参考图记录对应的磁盘路径键；没有 fileId / 记录丢了返回空串。 */
    private String pathKeyOfRef(ImageReference ref) {
        if (ref.getFileId() == null || ref.getFileId().trim().isEmpty()) {
            return "";
        }
        StoredFile stored;
        try {
            stored = files.require(ref.getFileId());
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        return NodeCanvasFiles.pathKey(files.resolve(stored).toString());
    }

    /** 文件实际所在的分类：决定 {@code /files/...} 地址的前缀。 */
    private String categoryOf(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (inside(paths.refs(), abs) || inside(paths.generated(), abs)) {
            return "refs";
        }
        if (inside(paths.materials(), abs)) {
            return "materials";
        }
        if (inside(paths.uploads(), abs)) {
            return "uploads";
        }
        if (inside(paths.videos(), abs)) {
            return "videos";
        }
        if (inside(paths.outputs(), abs)) {
            return "outputs";
        }
        return "refs";
    }

    private boolean inside(Path root, Path target) {
        return target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /**
     * 参考图视图。
     *
     * <p>原版 {@code asset_to_image_ref} 最后会把 {@code ref["localUrl"]} 覆盖成素材自己的地址
     * （素材住在 {@code uploads/} 下，而图库只认得 {@code materials/} 与 {@code generated/refs/}）。
     * Java 的 {@code image_references} 表没有 {@code localUrl} 列，所以这里按素材的实际位置输出。
     */
    private Map<String, Object> refView(ImageReference ref, Map<String, Object> asset, Path path) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ref.getId());
        out.put("originalName", ref.getOriginalName());
        out.put("fileName", path.getFileName().toString());
        out.put("localPath", path.toString());
        Object assetUrl = asset.get("localUrl");
        out.put("localUrl", assetUrl == null ? "" : String.valueOf(assetUrl));
        Object mime = asset.get("mimeType");
        out.put("mimeType", mime == null ? "" : String.valueOf(mime));
        out.put("size", com.lumenforge.entity.ncanvas.NodeCanvasGraph.longOf(asset.get("size")));
        out.put("url", "");
        out.put("source", ref.getSource());
        out.put("shared", Boolean.TRUE);
        out.put("createdAt", epochMillis(ref.getCreatedAt()));
        return out;
    }

    /** {@code LocalDateTime} → 毫秒时间戳（原版的 {@code createdAt} 是 {@code now_ms()}）。 */
    private long epochMillis(LocalDateTime value) {
        if (value == null) {
            return Ids.nowMs();
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

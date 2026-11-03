package com.lumenforge.service.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Ids;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.entity.image.ImageModel;
import com.lumenforge.entity.image.ImageReference;
import com.lumenforge.entity.image.ImageTask;
import com.lumenforge.entity.image.ImageTaskReference;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.mapper.canvas.CanvasMapper;
import com.lumenforge.mapper.image.ImageModelMapper;
import com.lumenforge.mapper.image.ImageReferenceMapper;
import com.lumenforge.mapper.image.ImageTaskMapper;
import com.lumenforge.mapper.image.ImageTaskReferenceMapper;
import com.lumenforge.provider.DownloadedImage;
import com.lumenforge.provider.ImageGenException;
import com.lumenforge.provider.ImageGenerationClient;
import com.lumenforge.provider.ImageProtocols;
import com.lumenforge.provider.ImageRequest;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.service.storage.FileStorageService;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片生成模块：模型管理、参考图库、任务队列与结果落盘。
 *
 * <p>对应 Python 版 app/api/image_tasks.py 与 app/image_jobs.py：
 * 8 种厂商协议由 {@link ImageGenerationClient} 负责，这里只管业务编排、持久化与文件登记。
 */
@Service
@Transactional
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageGenerationServiceImpl.class);

    /** 单张参考图上限 10MB，与 Python 版 MAX_REF_BYTES 对齐。 */
    private static final long MAX_REF_BYTES = 10L * 1024L * 1024L;
    /** 参考图库最多保留多少条，超出后淘汰最旧的。 */
    private static final int MAX_IMAGE_REFS = 120;
    /** 任务列表最多返回多少条。 */
    private static final int MAX_TASK_ROWS = 400;

    private static final List<String> ACTIVE_STATUSES = Collections.unmodifiableList(
            Arrays.asList("queued", "running"));

    private final ImageModelMapper models;
    private final ImageTaskMapper tasks;
    private final ImageReferenceMapper refs;
    private final ImageTaskReferenceMapper taskRefs;
    private final CanvasMapper canvases;
    private final CredentialCipher cipher;
    private final ImageGenerationClient provider;
    private final FileStorageService files;
    private final StoragePaths paths;
    private final ObjectMapper json;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    public ImageGenerationServiceImpl(ImageModelMapper models,
                                      ImageTaskMapper tasks,
                                      ImageReferenceMapper refs,
                                      ImageTaskReferenceMapper taskRefs,
                                      CanvasMapper canvases,
                                      CredentialCipher cipher,
                                      ImageGenerationClient provider,
                                      FileStorageService files,
                                      StoragePaths paths,
                                      ObjectMapper json) {
        this.models = models;
        this.tasks = tasks;
        this.refs = refs;
        this.taskRefs = taskRefs;
        this.canvases = canvases;
        this.cipher = cipher;
        this.provider = provider;
        this.files = files;
        this.paths = paths;
        this.json = json;
    }

    // ============================================================== 模型管理

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listModels() {
        List<ImageModel> rows = models.findAll();
        Collections.sort(rows, (a, b) -> {
            LocalDateTime left = a.getCreatedAt();
            LocalDateTime right = b.getCreatedAt();
            if (left == null && right == null) {
                return 0;
            }
            if (left == null) {
                return 1;
            }
            if (right == null) {
                return -1;
            }
            return right.compareTo(left);
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (ImageModel model : rows) {
            out.add(modelView(model));
        }
        return out;
    }

    @Override
    public Map<String, Object> createModel(Map<String, Object> body) {
        String name = Texts.str(body, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写模型名称");
        }
        String protocol = Texts.str(body, "protocol");
        if (protocol.isEmpty()) {
            protocol = ImageProtocols.ARK;
        }
        validateProtocol(protocol);
        String baseUrl = Texts.str(body, "baseUrl");
        if (baseUrl.isEmpty()) {
            baseUrl = ImageProtocols.defaultBase(protocol);
        }
        String modelName = Texts.str(body, "model");
        if (modelName.isEmpty()) {
            modelName = Texts.str(body, "modelName");
        }
        Map<String, Object> config = configFrom(body, Collections.<String, Object>emptyMap());
        String apiKey = Texts.str(body, "apiKey");
        ImageModel model = new ImageModel(
                Ids.nextId("im"),
                name,
                protocol,
                baseUrl,
                apiKey.isEmpty() ? null : cipher.encrypt(apiKey),
                modelName,
                Texts.asBool(body, "enabled", true),
                write(config));
        return modelView(models.save(model));
    }

    @Override
    public Map<String, Object> updateModel(String id, Map<String, Object> body) {
        ImageModel model = requireModel(id);
        String protocol = Texts.str(body, "protocol");
        if (!protocol.isEmpty()) {
            validateProtocol(protocol);
            // 换协议时如果没显式给地址，就跟着换到新协议的默认端点
            if (!protocol.equals(model.getProtocol()) && !Texts.has(body, "baseUrl")) {
                model.setBaseUrl(ImageProtocols.defaultBase(protocol));
            }
            model.setProtocol(protocol);
        }
        String name = Texts.str(body, "name");
        if (!name.isEmpty()) {
            model.setName(name);
        }
        String baseUrl = Texts.str(body, "baseUrl");
        if (!baseUrl.isEmpty()) {
            model.setBaseUrl(baseUrl);
        }
        if (Texts.has(body, "model") || Texts.has(body, "modelName")) {
            String modelName = Texts.str(body, "model");
            if (modelName.isEmpty()) {
                modelName = Texts.str(body, "modelName");
            }
            model.setModelName(modelName);
        }
        if (Texts.has(body, "enabled")) {
            model.setEnabled(Texts.asBool(body, "enabled", true));
        }
        // 前端回显的是掩码，只有真的传了值才覆盖，避免把真 Key 冲成掩码
        String apiKey = Texts.str(body, "apiKey");
        if (!apiKey.isEmpty()) {
            model.setApiKeyCiphertext(cipher.encrypt(apiKey));
        }
        model.setConfigJson(write(configFrom(body, readConfig(model.getConfigJson()))));
        return modelView(models.save(model));
    }

    @Override
    public Map<String, Object> deleteModel(String id) {
        ImageModel model = models.findById(id).orElse(null);
        if (model == null) {
            throw new IllegalArgumentException("图片模型不存在");
        }
        models.delete(model);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    // ============================================================== 任务

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTasks(int limit) {
        int size = Math.min(MAX_TASK_ROWS, Math.max(1, limit));
        List<ImageTask> rows = tasks.findTop400ByOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(Math.min(size, rows.size()));
        for (int i = 0; i < rows.size() && i < size; i++) {
            out.add(taskView(rows.get(i)));
        }
        return out;
    }

    @Override
    public Map<String, Object> createTask(Map<String, Object> body) {
        String modelId = Texts.str(body, "modelId");
        ImageModel model = modelId.isEmpty() ? null : models.findById(modelId).orElse(null);
        if (model == null) {
            throw new IllegalArgumentException("请选择图片模型");
        }
        if (!model.isEnabled()) {
            throw new IllegalArgumentException("图片模型「" + model.getName() + "」已停用");
        }
        String prompt = Texts.str(body, "prompt");
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("请填写提示词");
        }
        List<String> refIds = stringList(body.get("refIds"));
        List<ImageReference> picked = pickRefs(refIds);
        if (!refIds.isEmpty() && picked.isEmpty()) {
            throw new IllegalArgumentException("参考图已失效，请重新选择");
        }
        if (!picked.isEmpty() && !supportsImageInput(model)) {
            throw new IllegalArgumentException("图片模型「" + model.getName()
                    + "」未开启图生图，请在模型管理里勾选「支持图生图」或换一个模型");
        }
        if (picked.size() > ImageProtocols.MAX_REFS) {
            throw new IllegalArgumentException("参考图最多 " + ImageProtocols.MAX_REFS
                    + " 张，当前 " + picked.size() + " 张");
        }
        int count = Texts.asInt(body, "count", 1);
        count = Math.max(1, Math.min(ImageProtocols.MAX_COUNT, count));
        String size = Texts.str(body, "size");
        if (size.isEmpty()) {
            size = Texts.str(readConfig(model.getConfigJson()), "size");
        }
        ImageTask task = new ImageTask(model.getId(), model.getName(), prompt, size, count);
        task.setResultJson(write(resultOfRefs(Collections.<Map<String, Object>>emptyList(), picked)));
        ImageTask saved = tasks.save(task);
        linkRefs(saved.getId(), picked);
        return taskView(saved);
    }

    @Override
    public Map<String, Object> retryTask(String id) {
        ImageTask task = requireTask(id);
        if ("running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务正在生成中，无法重试");
        }
        if ("queued".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务仍在排队中，无需重试");
        }
        task.setStatus("queued");
        task.setErrorMessage(null);
        task.setFinishedAt(null);
        task.setResultJson(write(resultOfMaps(Collections.<Map<String, Object>>emptyList(), refsOf(task))));
        return taskView(tasks.save(task));
    }

    @Override
    public Map<String, Object> cancelTask(String id) {
        ImageTask task = requireTask(id);
        if ("running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务已在生成中，无法取消（请求已发出）");
        }
        if (!"queued".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务已结束，无需取消");
        }
        task.setStatus("cancelled");
        task.setFinishedAt(LocalDateTime.now());
        return taskView(tasks.save(task));
    }

    @Override
    public Map<String, Object> deleteTask(String id) {
        ImageTask task = requireTask(id);
        if (ACTIVE_STATUSES.contains(task.getStatus())) {
            throw new IllegalArgumentException("请先取消或等待任务结束");
        }
        // 结果图片可能被画布或参考图库共享，删任务时只在没人引用时才删文件
        for (Map<String, Object> image : imagesOf(task)) {
            String localPath = Texts.str(image, "localPath");
            if (!localPath.isEmpty()) {
                files.unlinkIfUnused(Paths.get(localPath));
            }
        }
        taskRefs.deleteByImageTaskId(task.getId());
        tasks.delete(task);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    // ============================================================== 参考图库

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRefs() {
        List<ImageReference> rows = refs.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (ImageReference ref : rows) {
            out.add(refView(ref));
        }
        return out;
    }

    @Override
    public Map<String, Object> uploadRef(byte[] buffer, String originalName, String mimeType) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("没有收到图片内容");
        }
        if (buffer.length > MAX_REF_BYTES) {
            throw new IllegalArgumentException("参考图单张不能超过 10MB");
        }
        String name = originalName == null || originalName.trim().isEmpty() ? "ref.png" : originalName.trim();
        String ext = FileStorageService.extensionOf(name);
        if (!FileStorageService.IMAGE_MIME_BY_EXT.containsKey(ext)) {
            throw new IllegalArgumentException("参考图只支持 png/jpg/jpeg/webp/bmp/gif 格式");
        }
        String resolvedMime = mimeType == null || mimeType.trim().isEmpty()
                ? FileStorageService.IMAGE_MIME_BY_EXT.get(ext)
                : mimeType.trim();
        StoredFile stored = files.store("refs", buffer, name, resolvedMime);
        ImageReference ref = new ImageReference(stored.getId(), null, name, "upload");
        ImageReference saved = refs.save(ref);
        trimRefs();
        return refView(saved);
    }

    @Override
    public Map<String, Object> deleteRef(String id) {
        ImageReference ref = refs.findById(id).orElse(null);
        if (ref == null) {
            throw new IllegalArgumentException("参考图不存在");
        }
        // 关联表对参考图是 RESTRICT，先解开再删，否则外键会拦下来
        taskRefs.deleteByReferenceId(ref.getId());
        refs.delete(ref);
        if (ref.getFileId() != null && !ref.getFileId().trim().isEmpty()) {
            files.delete(ref.getFileId());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    @Override
    public Map<String, Object> taskImageToRef(String taskId, Map<String, Object> body) {
        ImageTask task = requireTask(taskId);
        Map<String, Object> image = pickImage(task, Texts.str(body, "imageId"));
        String localPath = Texts.str(image, "localPath");
        if (localPath.isEmpty() || !Files.isRegularFile(Paths.get(localPath))) {
            throw new IllegalArgumentException("这张图片的本地文件已被删除，无法用作参考图");
        }
        String fileName = Texts.str(image, "fileName");
        byte[] buffer;
        try {
            buffer = Files.readAllBytes(Paths.get(localPath));
        } catch (IOException err) {
            throw new IllegalStateException("读取图片失败：" + err.getMessage(), err);
        }
        String ext = FileStorageService.extensionOf(fileName);
        if (!FileStorageService.IMAGE_MIME_BY_EXT.containsKey(ext)) {
            ext = ".png";
        }
        StoredFile stored = files.store("refs", buffer,
                Ids.nextId("ref") + ext, FileStorageService.IMAGE_MIME_BY_EXT.get(ext));
        ImageReference ref = new ImageReference(stored.getId(), null,
                fileName.isEmpty() ? stored.getStorageName() : fileName, "generated");
        ImageReference saved = refs.save(ref);
        trimRefs();
        return refView(saved);
    }

    @Override
    public Map<String, Object> reuseTaskRefs(String taskId) {
        ImageTask task = requireTask(taskId);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> snapshot : refsOf(task)) {
            String url = Texts.str(snapshot, "url");
            if (!url.isEmpty()) {
                // 外链参考图本来就不入库，直接还原成临时对象
                out.add(linkView(url, Texts.str(snapshot, "id")));
                continue;
            }
            String refId = Texts.str(snapshot, "id");
            if (!refId.isEmpty()) {
                ImageReference existing = refs.findById(refId).orElse(null);
                if (existing != null) {
                    out.add(refView(existing));
                    continue;
                }
            }
            String localPath = Texts.str(snapshot, "localPath");
            if (localPath.isEmpty() || !Files.isRegularFile(Paths.get(localPath))) {
                // 图库和磁盘文件都没了，这张参考图确实救不回来了
                continue;
            }
            try {
                byte[] buffer = Files.readAllBytes(Paths.get(localPath));
                String originalName = Texts.str(snapshot, "originalName");
                if (originalName.isEmpty()) {
                    originalName = Paths.get(localPath).getFileName().toString();
                }
                String mime = Texts.str(snapshot, "mimeType");
                StoredFile stored = files.store("refs", buffer, originalName,
                        mime.isEmpty() ? FileStorageService.guessMime(
                                FileStorageService.extensionOf(originalName)) : mime);
                ImageReference rebuilt = new ImageReference(stored.getId(), null, originalName, "generated");
                // 沿用原 id，重复点「复用」不会越登记越多
                rebuilt.adoptId(refId);
                ImageReference saved = refs.save(rebuilt);
                out.add(refView(saved));
            } catch (IOException err) {
                LOGGER.warn("复用参考图失败 {}：{}", localPath, err.getMessage());
            }
        }
        trimRefs();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("refs", out);
        return result;
    }

    @Override
    public Map<String, Object> taskImageToCanvas(String taskId, Map<String, Object> body) {
        ImageTask task = requireTask(taskId);
        Map<String, Object> image = pickImage(task, Texts.str(body, "imageId"));
        String localPath = Texts.str(image, "localPath");
        if (localPath.isEmpty() || !Files.isRegularFile(Paths.get(localPath))) {
            throw new IllegalArgumentException("这张图片的本地文件已被删除，无法送入");
        }
        String canvasId = Texts.str(body, "canvasId");
        Canvas canvas = canvasId.isEmpty() ? null : canvases.findById(canvasId).orElse(null);
        if (canvas == null) {
            throw new IllegalArgumentException("请选择要送入的画布");
        }
        Map<String, Object> config = readConfig(canvas.getConfigJson());
        List<Map<String, Object>> fields = mapList(config.get("fields"));
        Map<String, Object> assets = objectMap(config.get("assets"));
        String requestedKey = Texts.str(body, "fieldKey");

        Map<String, Object> field = null;
        if (!requestedKey.isEmpty()) {
            for (Map<String, Object> candidate : fields) {
                if (requestedKey.equals(fieldKey(candidate))) {
                    field = candidate;
                    break;
                }
            }
        } else {
            for (Map<String, Object> candidate : fields) {
                if ("IMAGE".equals(Texts.str(candidate, "fieldType").toUpperCase())) {
                    field = candidate;
                    break;
                }
            }
        }
        if (field == null) {
            throw new IllegalArgumentException("画布「" + canvas.getName()
                    + "」还没有图片输入参数，请先在该画布点「读取参数」");
        }
        if (!"IMAGE".equals(Texts.str(field, "fieldType").toUpperCase())) {
            throw new IllegalArgumentException("选中的参数不是图片类型");
        }
        String targetKey = fieldKey(field);
        String fileName = Texts.str(image, "fileName");
        if (fileName.isEmpty()) {
            fileName = Paths.get(localPath).getFileName().toString();
        }
        String ext = FileStorageService.extensionOf(fileName);
        String mime = FileStorageService.IMAGE_MIME_BY_EXT.containsKey(ext)
                ? FileStorageService.IMAGE_MIME_BY_EXT.get(ext) : "image/png";
        long size = 0L;
        try {
            size = Files.size(Paths.get(localPath));
        } catch (IOException ignored) {
            // 大小只用于展示，读不到就留 0
        }

        Map<String, Object> asset = new LinkedHashMap<String, Object>();
        asset.put("localPath", localPath);
        asset.put("originalName", fileName);
        asset.put("mimeType", mime);
        asset.put("size", size);
        asset.put("shared", true);
        asset.put("origin", "imageTask:" + task.getId());
        asset.put("localUrl", localUrlForLocalPath(localPath));
        asset.put("uploadedAt", System.currentTimeMillis());
        asset.put("fromImageTask", task.getId());

        // 目标位置原来的素材如果换了文件，且没人再引用，就顺手清掉
        Map<String, Object> prev = objectMap(assets.get(targetKey));
        String prevPath = Texts.str(prev, "localPath");
        assets.put(targetKey, asset);
        config.put("assets", assets);
        canvas.setConfigJson(write(config));
        canvases.save(canvas);
        if (!prevPath.isEmpty() && !prevPath.equals(localPath)) {
            files.unlinkIfUnused(Paths.get(prevPath));
        }

        // 把「这张图被谁用了」写回任务，前端好显示去向
        Map<String, Object> usedBy = new LinkedHashMap<String, Object>();
        usedBy.put("canvasId", canvas.getId());
        usedBy.put("canvasName", canvas.getName());
        usedBy.put("fieldKey", targetKey);
        usedBy.put("at", System.currentTimeMillis());
        List<Map<String, Object>> images = imagesOf(task);
        for (Map<String, Object> candidate : images) {
            if (Texts.str(candidate, "id").equals(Texts.str(image, "id"))) {
                candidate.put("usedBy", usedBy);
            }
        }
        task.setResultJson(write(resultOfMaps(images, refsOf(task))));
        tasks.save(task);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("canvasId", canvas.getId());
        out.put("canvasName", canvas.getName());
        String description = Texts.str(field, "description");
        out.put("field", description.isEmpty() ? Texts.str(field, "fieldName") : description);
        return out;
    }

    @Override
    public Map<String, Object> importImage(Map<String, Object> body) {
        String localPath = Texts.str(body, "localPath");
        if (localPath.isEmpty()) {
            throw new IllegalArgumentException("本地文件不在允许的临时目录里，已拒绝");
        }
        Path uploadsDir = paths.uploads().toAbsolutePath().normalize();
        Path root = uploadsDir.getParent() == null
                ? null : uploadsDir.getParent().resolve("douyin-tmp").normalize();
        Path target = Paths.get(localPath).toAbsolutePath().normalize();
        if (root == null || !target.startsWith(root)) {
            throw new IllegalArgumentException("本地文件不在允许的临时目录里，已拒绝");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("这张图的本地文件已不在，无法导入");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException err) {
            throw new IllegalStateException("读取文件失败：" + err.getMessage(), err);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("拿到的文件是空的");
        }
        byte[] buffer;
        try {
            buffer = Files.readAllBytes(target);
        } catch (IOException err) {
            throw new IllegalStateException("读取文件失败：" + err.getMessage(), err);
        }
        String ext = FileStorageService.extensionOf(target.getFileName().toString());
        if (ext.isEmpty()) {
            ext = ".png";
        }
        String storedName = Ids.nextId("gen") + ext;
        StoredFile stored = files.store("generated", buffer, storedName,
                FileStorageService.IMAGE_MIME_BY_EXT.containsKey(ext)
                        ? FileStorageService.IMAGE_MIME_BY_EXT.get(ext) : FileStorageService.guessMime(ext));

        String platform = Texts.str(body, "platform");
        if (platform.isEmpty()) {
            platform = "web";
        }
        if (platform.length() > 40) {
            platform = platform.substring(0, 40);
        }
        String prompt = Texts.truncate(Texts.str(body, "prompt"), 2000);
        ImageTask task = new ImageTask(null, "网页 · " + platform, prompt, "", 1);
        task.setStatus("success");
        task.setFinishedAt(LocalDateTime.now());

        Map<String, Object> image = new LinkedHashMap<String, Object>();
        image.put("id", Ids.nextId("gi"));
        image.put("fileName", stored.getStorageName());
        image.put("localPath", files.resolve(stored).toString());
        image.put("localUrl", files.localUrlFor(stored));
        image.put("remoteUrl", Texts.str(body, "sourceUrl"));
        image.put("size", stored.getSizeBytes());
        image.put("usedBy", null);
        ImageTask saved = tasks.save(task);
        List<Map<String, Object>> images = new ArrayList<Map<String, Object>>(1);
        images.add(image);
        saved.setResultJson(write(resultOfMaps(images, Collections.<Map<String, Object>>emptyList())));
        tasks.save(saved);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("taskId", saved.getId());
        out.put("image", image);
        return out;
    }

    // ============================================================== 队列

    @Scheduled(fixedDelayString = "${app.image.scheduler.delay-ms:1500}")
    @Override
    public void processQueue() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ImageTask> queued = tasks.findByStatusOrderByCreatedAtAsc("queued");
            if (queued.isEmpty()) {
                return;
            }
            runTask(queued.get(0));
        } catch (Exception err) {
            LOGGER.warn("图片队列处理异常：{}", err.getMessage());
        } finally {
            processing.set(false);
        }
    }

    private void runTask(ImageTask task) {
        ImageModel model = task.getModelId() == null ? null : models.findById(task.getModelId()).orElse(null);
        task.setStatus("running");
        task.setAttempts(task.getAttempts() + 1);
        if (model == null) {
            task.setStatus("failed");
            task.setErrorMessage("图片模型已被删除");
            task.setFinishedAt(LocalDateTime.now());
            tasks.save(task);
            return;
        }
        tasks.saveAndFlush(task);
        try {
            ImageRequest request = buildRequest(model, task);
            List<Map<String, Object>> items = provider.generate(request);
            List<Map<String, Object>> images = new ArrayList<Map<String, Object>>(items.size());
            for (Map<String, Object> item : items) {
                DownloadedImage downloaded = provider.download(item);
                StoredFile stored = files.store("generated", downloaded.getBytes(),
                        Ids.nextId("gen") + downloaded.getExtension(),
                        mimeOfExtension(downloaded.getExtension()));
                Map<String, Object> image = new LinkedHashMap<String, Object>();
                image.put("id", Ids.nextId("gi"));
                image.put("fileName", stored.getStorageName());
                image.put("localPath", files.resolve(stored).toString());
                image.put("localUrl", files.localUrlFor(stored));
                image.put("remoteUrl", Texts.str(item, "url"));
                image.put("size", stored.getSizeBytes());
                image.put("usedBy", null);
                images.add(image);
            }
            task.setStatus("success");
            task.setErrorMessage(null);
            task.setResultJson(write(resultOfMaps(images, refsOf(task))));
        } catch (ImageGenException err) {
            task.setStatus("failed");
            task.setErrorMessage(err.getMessage());
        } catch (Exception err) {
            task.setStatus("failed");
            task.setErrorMessage(err.getMessage() == null ? "图片生成失败" : err.getMessage());
        } finally {
            task.setFinishedAt(LocalDateTime.now());
            tasks.save(task);
        }
    }

    /**
     * 服务重启时，还停在 running 的任务上游可能已经扣过费。
     * 直接标失败并提示人工确认，避免自动重试造成重复扣费。
     */
    @PostConstruct
    public void markInterruptedTasks() {
        List<ImageTask> running = tasks.findByStatusOrderByCreatedAtAsc("running");
        if (running.isEmpty()) {
            return;
        }
        for (ImageTask task : running) {
            task.setStatus("failed");
            task.setErrorMessage("服务在图片生成过程中重启；请确认厂商侧记录后手动重试，避免重复扣费");
            task.setFinishedAt(LocalDateTime.now());
            tasks.save(task);
        }
        LOGGER.info("已把 {} 条中断的图片任务标记为失败", running.size());
    }

    private ImageRequest buildRequest(ImageModel model, ImageTask task) {
        Map<String, Object> config = readConfig(model.getConfigJson());
        String apiKey = model.getApiKeyCiphertext() == null
                ? "" : cipher.decrypt(model.getApiKeyCiphertext());
        ImageRequest request = new ImageRequest();
        request.setProtocol(model.getProtocol());
        request.setBaseUrl(model.getBaseUrl());
        request.setApiKey(apiKey);
        request.setAkSecret(decryptConfigSecret(config, "akSecret"));
        request.setAccountId(Texts.str(config, "accountId"));
        request.setModelName(model.getModelName());
        request.setDisplayName(model.getName());
        request.setPrompt(task.getPrompt());
        request.setSize(task.getSize());
        request.setCount(task.getImageCount());
        request.setNoWatermark(Texts.asBool(config.get("noWatermark"), true));
        request.setSupportsImageInput(Texts.asBool(config.get("supportsImageInput"), false));
        request.setExtraParams(Texts.str(config, "extraParams"));

        for (Map<String, Object> snapshot : refsOf(task)) {
            ImageRequest.Ref ref = new ImageRequest.Ref();
            ref.setId(Texts.str(snapshot, "id"));
            ref.setOriginalName(Texts.str(snapshot, "originalName"));
            ref.setMimeType(Texts.str(snapshot, "mimeType"));
            ref.setSize(Texts.asLong(snapshot.get("size"), 0L));
            String url = Texts.str(snapshot, "url");
            if (!url.isEmpty()) {
                ref.setUrl(url);
                request.addRef(ref);
                continue;
            }
            String localPath = Texts.str(snapshot, "localPath");
            if (localPath.isEmpty() || !Files.isRegularFile(Paths.get(localPath))) {
                continue;
            }
            try {
                byte[] buffer = Files.readAllBytes(Paths.get(localPath));
                String mime = Texts.str(snapshot, "mimeType");
                if (mime.isEmpty()) {
                    mime = mimeOfExtension(FileStorageService.extensionOf(localPath));
                }
                ref.setDataUri(DownloadedImage.toDataUri(buffer, mime));
                request.addRef(ref);
            } catch (IOException err) {
                LOGGER.warn("读取参考图失败 {}：{}", localPath, err.getMessage());
            }
        }
        return request;
    }

    // ============================================================== 视图与工具

    private Map<String, Object> modelView(ImageModel model) {
        Map<String, Object> config = readConfig(model.getConfigJson());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", model.getId());
        out.put("name", model.getName());
        out.put("protocol", model.getProtocol());
        out.put("baseUrl", model.getBaseUrl());
        out.put("model", model.getModelName());
        out.put("enabled", model.isEnabled());
        out.put("createdAt", model.getCreatedAt() == null ? null : model.getCreatedAt().toString());
        out.put("size", Texts.str(config, "size"));
        out.put("noWatermark", Texts.asBool(config.get("noWatermark"), true));
        out.put("supportsImageInput", Texts.asBool(config.get("supportsImageInput"), false));
        out.put("extraParams", Texts.str(config, "extraParams"));
        String akSecret = decryptConfigSecret(config, "akSecret");
        out.put("akSecret", mask(akSecret));
        out.put("hasSecret", !akSecret.isEmpty());
        out.put("accountId", Texts.str(config, "accountId"));
        String apiKey = model.getApiKeyCiphertext() == null
                ? "" : cipher.decrypt(model.getApiKeyCiphertext());
        out.put("hasKey", !apiKey.isEmpty());
        out.put("apiKey", mask(apiKey));
        return out;
    }

    private Map<String, Object> taskView(ImageTask task) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", task.getId());
        out.put("modelId", task.getModelId());
        out.put("modelName", task.getModelName());
        out.put("prompt", task.getPrompt());
        out.put("size", task.getSize());
        out.put("count", task.getImageCount());
        out.put("status", task.getStatus());
        out.put("images", imagesOf(task));
        out.put("refs", refsOf(task));
        out.put("error", task.getErrorMessage() == null ? "" : task.getErrorMessage());
        out.put("attempts", task.getAttempts());
        out.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        out.put("finishedAt", task.getFinishedAt() == null ? null : task.getFinishedAt().toString());
        return out;
    }

    private Map<String, Object> refView(ImageReference ref) {
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

    private Map<String, Object> linkView(String url, String id) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", id == null || id.trim().isEmpty() ? Ids.nextId("link") : id);
        out.put("source", "link");
        out.put("originalName", url.endsWith("/") ? "link" : lastSegment(url));
        out.put("fileName", "");
        out.put("localPath", "");
        out.put("localUrl", url);
        out.put("mimeType", "");
        out.put("size", 0L);
        out.put("url", url);
        out.put("createdAt", LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> configFrom(Map<String, Object> body, Map<String, Object> old) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.putAll(old);
        config.put("size", Texts.str(body, "size"));
        config.put("noWatermark", Texts.asBool(body, "noWatermark", true));
        config.put("supportsImageInput", Texts.asBool(body, "supportsImageInput", false));
        String extra = Texts.str(body, "extraParams");
        if (!extra.isEmpty()) {
            Object parsed;
            try {
                parsed = json.readValue(extra, Object.class);
            } catch (Exception err) {
                throw new IllegalArgumentException("额外参数格式错误：" + err.getMessage());
            }
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("额外参数必须是 JSON 对象，例如 {\"seed\": 12345}");
            }
            config.put("extraParams", extra);
        }
        // 密钥类字段只在真的传了值才覆盖；前端回显的是掩码
        String akSecret = Texts.str(body, "akSecret");
        if (!akSecret.isEmpty()) {
            config.put("akSecret", cipher.encrypt(akSecret));
        } else if (!config.containsKey("akSecret")) {
            config.put("akSecret", "");
        }
        String accountId = Texts.str(body, "accountId");
        if (!accountId.isEmpty()) {
            config.put("accountId", accountId);
        } else if (!config.containsKey("accountId")) {
            config.put("accountId", "");
        }
        return config;
    }

    private List<ImageReference> pickRefs(List<String> items) {
        List<ImageReference> out = new ArrayList<ImageReference>();
        for (String item : items) {
            if (out.size() >= ImageProtocols.MAX_REFS) {
                break;
            }
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            ImageReference ref = refs.findById(item.trim()).orElse(null);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    private void linkRefs(String taskId, List<ImageReference> picked) {
        for (int i = 0; i < picked.size(); i++) {
            taskRefs.save(new ImageTaskReference(taskId, picked.get(i).getId(), i));
        }
    }

    private void trimRefs() {
        List<ImageReference> rows = refs.findAllByOrderByCreatedAtDesc();
        for (int i = MAX_IMAGE_REFS; i < rows.size(); i++) {
            taskRefs.deleteByReferenceId(rows.get(i).getId());
            refs.delete(rows.get(i));
        }
    }

    private boolean supportsImageInput(ImageModel model) {
        Map<String, Object> config = readConfig(model.getConfigJson());
        if (ImageProtocols.ZHIPU.equals(model.getProtocol())) {
            return false;
        }
        if (Texts.asBool(config.get("supportsImageInput"), false)) {
            return true;
        }
        // 方舟默认就能吃参考图，除非显式关掉
        return ImageProtocols.ARK.equals(model.getProtocol()) && !config.containsKey("supportsImageInput");
    }

    private ImageTask requireTask(String id) {
        return tasks.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("生成记录不存在"));
    }

    private ImageModel requireModel(String id) {
        return models.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("图片模型不存在"));
    }

    private Map<String, Object> pickImage(ImageTask task, String imageId) {
        List<Map<String, Object>> images = imagesOf(task);
        if (images.isEmpty()) {
            throw new IllegalArgumentException("找不到这张图片");
        }
        if (imageId == null || imageId.trim().isEmpty()) {
            return images.get(0);
        }
        for (Map<String, Object> image : images) {
            if (imageId.trim().equals(Texts.str(image, "id"))) {
                return image;
            }
        }
        throw new IllegalArgumentException("找不到这张图片");
    }

    private List<Map<String, Object>> imagesOf(ImageTask task) {
        return mapList(readConfig(task.getResultJson()).get("images"));
    }

    private List<Map<String, Object>> refsOf(ImageTask task) {
        return mapList(readConfig(task.getResultJson()).get("refs"));
    }

    /** 把参考图实体转成任务里保存的快照；快照自带路径，图库被清也不影响历史记录。 */
    private Map<String, Object> resultOfRefs(List<Map<String, Object>> images, List<ImageReference> picked) {
        List<Map<String, Object>> snapshots = new ArrayList<Map<String, Object>>(picked.size());
        for (ImageReference ref : picked) {
            Map<String, Object> view = refView(ref);
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("id", view.get("id"));
            snapshot.put("originalName", view.get("originalName"));
            snapshot.put("fileName", view.get("fileName"));
            snapshot.put("localPath", view.get("localPath"));
            snapshot.put("localUrl", view.get("localUrl"));
            snapshot.put("mimeType", view.get("mimeType"));
            snapshot.put("size", view.get("size"));
            snapshot.put("url", view.get("url"));
            snapshot.put("source", view.get("source"));
            snapshots.add(snapshot);
        }
        return resultOfMaps(images, snapshots);
    }

    private Map<String, Object> resultOfMaps(List<Map<String, Object>> images, List<Map<String, Object>> snapshots) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("images", images);
        result.put("refs", snapshots);
        return result;
    }

    private void validateProtocol(String protocol) {
        if (!ImageProtocols.PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException(ImageProtocols.PROTOCOL_HELP);
        }
    }

    private Map<String, Object> readConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = json.readValue(raw, Object.class);
            if (parsed instanceof Map) {
                return objectMap(parsed);
            }
        } catch (Exception err) {
            LOGGER.warn("JSON 解析失败：{}", err.getMessage());
        }
        return new LinkedHashMap<String, Object>();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception err) {
            throw new IllegalStateException("无法序列化 JSON：" + err.getMessage(), err);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof Map) {
                    out.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private String decryptConfigSecret(Map<String, Object> config, String key) {
        String raw = Texts.str(config, key);
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return cipher.decrypt(raw);
        } catch (RuntimeException err) {
            LOGGER.warn("图片模型配置项 {} 解密失败：{}", key, err.getMessage());
            return "";
        }
    }

    private String fieldKey(Map<String, Object> field) {
        return Texts.str(field, "nodeId") + "::" + Texts.str(field, "fieldName");
    }

    private String localUrlForLocalPath(String localPath) {
        Path path = Paths.get(localPath).toAbsolutePath().normalize();
        Path generated = paths.generated().toAbsolutePath().normalize();
        Path refsDir = paths.refs().toAbsolutePath().normalize();
        if (path.startsWith(refsDir)) {
            return "/files/generated/refs/" + path.getFileName();
        }
        if (path.startsWith(generated)) {
            return "/files/generated/" + path.getFileName();
        }
        return "/files/uploads/" + path.getFileName();
    }

    private String mimeOfExtension(String ext) {
        String mime = FileStorageService.IMAGE_MIME_BY_EXT.get(ext == null ? "" : ext.toLowerCase());
        return mime == null ? "image/png" : mime;
    }

    private String mask(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "••••";
        }
        return value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
    }

    private String lastSegment(String url) {
        int slash = url.lastIndexOf('/');
        String tail = slash < 0 ? url : url.substring(slash + 1);
        return tail.isEmpty() ? "link" : tail;
    }
}

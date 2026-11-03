package com.lumenforge.service.video;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Ids;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.image.ImageTask;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.entity.video.VideoInput;
import com.lumenforge.entity.video.VideoModel;
import com.lumenforge.entity.video.VideoTask;
import com.lumenforge.mapper.image.ImageTaskMapper;
import com.lumenforge.mapper.storage.StoredFileMapper;
import com.lumenforge.mapper.video.VideoInputMapper;
import com.lumenforge.mapper.video.VideoModelMapper;
import com.lumenforge.mapper.video.VideoTaskMapper;
import com.lumenforge.provider.VideoGenerationClient;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.service.storage.FileStorageService;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频生成业务实现，逐函数对齐 Python 版 {@code app/video_jobs.py} + {@code app/api/video_api.py}。
 *
 * <p>调度用「状态 + 时间戳」驱动：定时器每 3 秒跳一次，任务自己带 {@code nextPollAt}，
 * 到点才动它。这样重启接管是白送的 —— {@code status='polling'} 且时间戳已过期的任务
 * 会被自然捡起来继续轮询。一次只推进一条，避免同一时刻并发打满厂商接口。
 */
@Service
public class VideoGenerationServiceImpl implements VideoGenerationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoGenerationServiceImpl.class);

    /** 三家都建议 15~30 秒查一次，取 15 秒。 */
    private static final long POLL_INTERVAL_MS = 15000L;
    /** 连续查询失败这么多次才判失败，避免网络抖动直接杀掉任务。 */
    private static final int MAX_POLL_ERRORS = 10;
    /** 上传输入图的上限，与 Python 版 {@code raw_body(limit=40MB)} 一致。 */
    private static final long MAX_INPUT_BYTES = 40L * 1024L * 1024L;
    private static final int MAX_TASK_ROWS = 400;
    private static final int MAX_INPUT_ROWS = 40;

    /** 会被 worker 捡起来继续推进的状态。 */
    private static final List<String> ACTIVE_STATUSES = Collections.unmodifiableList(
            Arrays.asList("queued", "polling", "downloading"));
    /** 正在占着厂商那边资源的状态（不含 queued：还没提交，不算占位）。 */
    private static final List<String> IN_FLIGHT_STATUSES = Collections.unmodifiableList(
            Arrays.asList("submitting", "polling", "downloading"));

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private final VideoModelMapper models;
    private final VideoInputMapper inputs;
    private final VideoTaskMapper tasks;
    private final ImageTaskMapper imageTasks;
    private final StoredFileMapper storedFiles;
    private final FileStorageService files;
    private final StoragePaths paths;
    private final CredentialCipher cipher;
    private final VideoGenerationClient provider;
    private final ObjectMapper json;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    public VideoGenerationServiceImpl(VideoModelMapper models, VideoInputMapper inputs, VideoTaskMapper tasks,
                                      ImageTaskMapper imageTasks, StoredFileMapper storedFiles,
                                      FileStorageService files, StoragePaths paths, CredentialCipher cipher,
                                      VideoGenerationClient provider, ObjectMapper json) {
        this.models = models;
        this.inputs = inputs;
        this.tasks = tasks;
        this.imageTasks = imageTasks;
        this.storedFiles = storedFiles;
        this.files = files;
        this.paths = paths;
        this.cipher = cipher;
        this.provider = provider;
        this.json = json;
    }

    /**
     * 启动接管：{@code submitting} 是「请求发出去了但结果没回来」的中间态，要分两种处理。
     * 没拿到远端任务 ID 说明提交结果未知，重提可能重复扣费 → 直接判失败；
     * 已有远端任务 ID 说明其实提交成功了 → 转成轮询。
     */
    @PostConstruct
    public void adoptTasks() {
        List<VideoTask> rows = tasks.findByStatusOrderByCreatedAtAsc("submitting");
        int changed = 0;
        for (VideoTask task : rows) {
            VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
            if (!text(task.getRemoteTaskId()).isEmpty()) {
                task.setStatus("polling");
                task.setErrorMessage("");
                state.nextPollAt = 0;
            } else {
                task.setStatus("failed");
                task.setErrorMessage("程序重启时这次提交的结果未知，请重新生成（避免重复扣费）");
                task.setFinishedAt(LocalDateTime.now());
            }
            task.setResultJson(state.write(json));
            tasks.save(task);
            changed++;
        }
        if (changed > 0) {
            LOGGER.info("已接管 {} 条提交中断的视频任务", changed);
        }
    }

    // ================================================================ 模型

    @Override
    public List<Map<String, Object>> listModels() {
        List<VideoModel> rows = models.findAll();
        Collections.sort(rows, (left, right) -> {
            LocalDateTime a = left.getCreatedAt();
            LocalDateTime b = right.getCreatedAt();
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return b.compareTo(a);
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (VideoModel row : rows) {
            out.add(modelView(row));
        }
        return out;
    }

    @Override
    public Map<String, Object> createModel(Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<String, Object>() : body;
        String name = Texts.trim(safe.get("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写模型名称");
        }
        String provider = "zhipu";
        VideoModel model = new VideoModel(name, provider, VideoGenerationClient.presetBaseUrl(provider),
                null, "", true, writeConfig(defaultConfig()));
        applyModelBody(model, safe);
        models.save(model);
        return modelView(model);
    }

    @Override
    public Map<String, Object> updateModel(String id, Map<String, Object> body) {
        VideoModel model = requireModel(id);
        applyModelBody(model, body == null ? new LinkedHashMap<String, Object>() : body);
        models.save(model);
        return modelView(model);
    }

    @Override
    public Map<String, Object> deleteModel(String id) {
        VideoModel model = requireModel(id);
        models.delete(model);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    /**
     * 按 Python 版 {@code apply_video_model_body} 的覆盖顺序处理：provider → name → baseUrl
     * → apiKey →（model / 默认时长 / 分辨率 / 比例）→ enabled → extraParams。
     */
    private void applyModelBody(VideoModel model, Map<String, Object> body) {
        if (body.containsKey("provider") && body.get("provider") != null) {
            String provider = Texts.trim(body.get("provider"));
            if (VideoGenerationClient.preset(provider) == null) {
                throw new IllegalArgumentException("服务商只支持 zhipu / ark / dashscope / doubao");
            }
            boolean changed = !provider.equals(model.getProvider());
            model.setProvider(provider);
            if (changed && Texts.trim(body.get("baseUrl")).isEmpty()) {
                model.setBaseUrl(VideoGenerationClient.presetBaseUrl(provider));
            }
        }
        if (body.containsKey("name") && body.get("name") != null) {
            String name = Texts.trim(body.get("name"));
            if (!name.isEmpty()) {
                model.setName(name);
            }
        }
        if (body.containsKey("baseUrl")) {
            model.setBaseUrl(Texts.trim(body.get("baseUrl")));
        }
        String apiKey = Texts.trim(body.get("apiKey"));
        if (!apiKey.isEmpty()) {
            model.setApiKeyCiphertext(cipher.encrypt(apiKey));
        }
        Map<String, Object> config = readConfig(model.getConfigJson());
        for (String key : Arrays.asList("model", "defaultDuration", "defaultResolution", "defaultRatio")) {
            if (body.containsKey(key)) {
                if ("model".equals(key)) {
                    model.setModelName(Texts.trim(body.get(key)));
                } else {
                    config.put(key, Texts.trim(body.get(key)));
                }
            }
        }
        if (body.containsKey("enabled")) {
            model.setEnabled(Texts.asBool(body.get("enabled"), true));
        }
        if (body.containsKey("extraParams")) {
            String raw = Texts.trim(body.get("extraParams"));
            if (!raw.isEmpty()) {
                Object parsed;
                try {
                    parsed = json.readValue(raw, Object.class);
                } catch (Exception err) {
                    throw new IllegalArgumentException("额外参数格式错误：" + err.getMessage());
                }
                if (!(parsed instanceof Map)) {
                    throw new IllegalArgumentException("额外参数必须是 JSON 对象，例如 {\"seed\": 12345}");
                }
            }
            config.put("extraParams", raw);
        }
        model.setConfigJson(writeConfig(config));
    }

    private Map<String, Object> modelView(VideoModel model) {
        Map<String, Object> config = readConfig(model.getConfigJson());
        String ciphertext = model.getApiKeyCiphertext() == null ? "" : model.getApiKeyCiphertext();
        String masked = "";
        if (!ciphertext.isEmpty()) {
            String plain = decryptQuietly(ciphertext);
            masked = plain.isEmpty() ? "****" : maskKey(plain);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", model.getId());
        out.put("name", model.getName());
        out.put("provider", model.getProvider());
        out.put("baseUrl", model.getBaseUrl());
        out.put("apiKey", masked);
        out.put("model", model.getModelName());
        out.put("defaultDuration", Texts.str(config, "defaultDuration", ""));
        out.put("defaultResolution", Texts.str(config, "defaultResolution", ""));
        out.put("defaultRatio", Texts.str(config, "defaultRatio", ""));
        out.put("extraParams", Texts.str(config, "extraParams", ""));
        out.put("enabled", model.isEnabled());
        out.put("createdAt", millis(model.getCreatedAt()));
        out.put("hasKey", !ciphertext.isEmpty());
        out.put("supportsLastFrame", VideoGenerationClient.lastFrameSupported(
                model.getProvider(), model.getModelName()));
        out.put("takesSecondImage", VideoGenerationClient.takesSecondImage(
                model.getProvider(), model.getModelName()));
        return out;
    }

    // ================================================================ 输入图库

    @Override
    public List<Map<String, Object>> listInputs() {
        List<VideoInput> rows = inputs.findAll();
        Collections.sort(rows, (left, right) -> {
            long a = millis(left.getCreatedAt());
            long b = millis(right.getCreatedAt());
            return Long.compare(b, a);
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (VideoInput row : rows) {
            if (out.size() >= MAX_INPUT_ROWS) break;
            Map<String, Object> view = inputSnapshot(row);
            if (view != null) out.add(view);
        }
        return out;
    }

    @Override
    public Map<String, Object> uploadInput(byte[] buffer, String originalName, String mimeType) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("没有收到图片内容");
        }
        if (buffer.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("上传内容太大（" + fmtMb(buffer.length)
                    + "），上限 " + fmtMb(MAX_INPUT_BYTES));
        }
        String name = originalName == null || originalName.trim().isEmpty() ? "input.png" : originalName.trim();
        String ext = FileStorageService.extensionOf(name);
        String resolvedMime = VideoGenerationClient.INPUT_MIME_BY_EXT.get(ext);
        if (resolvedMime == null) {
            throw new IllegalArgumentException("输入图只支持 " + String.join(" / ",
                    VideoGenerationClient.INPUT_MIME_BY_EXT.keySet()) + " 格式");
        }
        StoredFile stored = files.store("videoInputs", buffer, name, resolvedMime);
        VideoInput input = new VideoInput(stored.getId(), "upload", null);
        inputs.save(input);
        return inputRecord(input, stored);
    }

    @Override
    public Map<String, Object> deleteInput(String id) {
        VideoInput input = inputs.findById(id).orElseThrow(
                () -> new IllegalArgumentException("输入图不存在"));
        StoredFile stored = findStoredQuietly(input.getFileId());
        inputs.delete(input);
        if (stored != null) {
            if ("fromImage".equals(input.getSource())) {
                // 共享引用：文件还在图片任务那边用着，只数引用，不动登记记录
                files.unlinkIfUnused(files.resolve(stored));
            } else {
                files.delete(stored.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    /** 输入图的完整记录，对应 Python 版 {@code new_video_input} 的字段集合。 */
    private Map<String, Object> inputRecord(VideoInput input, StoredFile stored) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", input.getId());
        out.put("fileName", stored.getStorageName());
        out.put("originalName", stored.getOriginalName() == null || stored.getOriginalName().trim().isEmpty()
                ? stored.getStorageName() : stored.getOriginalName());
        out.put("localPath", files.resolve(stored).toString());
        out.put("localUrl", files.localUrlFor(stored));
        out.put("mimeType", stored.getMimeType());
        out.put("size", stored.getSizeBytes());
        out.put("source", input.getSource() == null ? "" : input.getSource());
        out.put("sourceTaskId", input.getSourceTaskId() == null ? "" : input.getSourceTaskId());
        out.put("createdAt", millis(input.getCreatedAt()));
        if ("fromImage".equals(input.getSource())) {
            out.put("shared", true);
        }
        return out;
    }

    /** 列表用的输入图快照，对应 Python 版 {@code video_input_snapshot}。 */
    private Map<String, Object> inputSnapshot(VideoInput input) {
        StoredFile stored = findStoredQuietly(input.getFileId());
        if (stored == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", input.getId());
        out.put("fileName", stored.getStorageName());
        out.put("originalName", stored.getOriginalName() == null || stored.getOriginalName().trim().isEmpty()
                ? stored.getStorageName() : stored.getOriginalName());
        out.put("localUrl", files.localUrlFor(stored));
        out.put("mimeType", stored.getMimeType());
        out.put("size", stored.getSizeBytes());
        out.put("source", input.getSource() == null ? "" : input.getSource());
        out.put("shared", "fromImage".equals(input.getSource()));
        out.put("createdAt", millis(input.getCreatedAt()));
        return out;
    }

    /** 任务里保存的输入图快照：路径给执行用（读成 data URI），URL 给历史记录展示用。 */
    private Map<String, Object> executionSnapshot(VideoInput input, StoredFile stored) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", input.getId());
        out.put("fileName", stored.getStorageName());
        out.put("originalName", stored.getOriginalName() == null || stored.getOriginalName().trim().isEmpty()
                ? stored.getStorageName() : stored.getOriginalName());
        out.put("localPath", files.resolve(stored).toString());
        out.put("localUrl", files.localUrlFor(stored));
        out.put("mimeType", stored.getMimeType());
        out.put("size", stored.getSizeBytes());
        return out;
    }

    // ================================================================ 任务

    @Override
    public List<Map<String, Object>> listTasks(int limit) {
        List<VideoTask> rows = tasks.findTop400ByOrderByCreatedAtDesc();
        int capped = Math.min(MAX_TASK_ROWS, Math.max(1, limit));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(Math.min(rows.size(), capped));
        for (VideoTask row : rows) {
            if (out.size() >= capped) break;
            out.add(taskView(row));
        }
        return out;
    }

    @Override
    public Map<String, Object> createTask(Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<String, Object>() : body;
        String modelId = Texts.str(safe, "modelId");
        VideoModel model = modelId.isEmpty() ? null : models.findById(modelId).orElse(null);
        if (model == null) {
            throw new IllegalArgumentException("请选择视频模型");
        }
        if (!model.isEnabled()) {
            throw new IllegalArgumentException("视频模型「" + model.getName() + "」已停用");
        }
        String prompt = Texts.trim(safe.get("prompt"));
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("请填写提示词");
        }
        String inputId = Texts.str(safe, "inputId");
        VideoInput input = inputId.isEmpty() ? null : inputs.findById(inputId).orElse(null);
        if (input == null) {
            throw new IllegalArgumentException("请先添加输入图片（也可以用自用模块结果图上的「送去视频」）");
        }
        String endInputId = Texts.str(safe, "endInputId");
        VideoInput endInput = endInputId.isEmpty() ? null : inputs.findById(endInputId).orElse(null);
        // 模型不认尾帧时不报错 —— 画布上照样连着两张图，只是画面终点改由提示词描述
        Map<String, Object> config = readConfig(model.getConfigJson());
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("duration", paramText(safe, "duration", Texts.str(config, "defaultDuration", "")));
        params.put("ratio", paramText(safe, "ratio", Texts.str(config, "defaultRatio", "")));
        params.put("resolution", paramText(safe, "resolution", Texts.str(config, "defaultResolution", "")));
        params.put("seed", safe.get("seed") == null ? "" : String.valueOf(safe.get("seed")));
        String tier = Texts.trim(safe.get("tier"));
        if (!tier.isEmpty()) {
            params.put("tier", tier);
        }

        VideoTask task = new VideoTask(model.getId(), model.getName(), prompt, writeJson(params),
                input.getId(), endInput == null ? null : endInput.getId());
        VideoTaskResult state = new VideoTaskResult();
        state.provider = model.getProvider() == null ? "" : model.getProvider();
        state.input = snapshotOf(input);
        state.endInput = endInput == null ? null : snapshotOf(endInput);
        task.setResultJson(state.write(json));
        tasks.save(task);
        return taskView(task);
    }

    @Override
    public Map<String, Object> retryTask(String id) {
        VideoTask task = requireTask(id);
        if (ACTIVE_STATUSES.contains(task.getStatus())) {
            throw new IllegalArgumentException("任务正在进行中，无法重试");
        }
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        task.setStatus("queued");
        task.setErrorMessage("");
        // 远端任务号有唯一索引，重排队时必须真正置空，不能写空串
        task.setRemoteTaskId(null);
        task.setFinishedAt(null);
        state.video = null;
        state.remoteStatus = "";
        state.remoteVideoUrl = "";
        state.nextPollAt = 0;
        state.pollErrors = 0;
        state.cancelRequested = false;
        state.triedAccounts = new ArrayList<String>();
        state.startedAt = null;
        task.setResultJson(state.write(json));
        tasks.save(task);
        return taskView(task);
    }

    @Override
    public Map<String, Object> cancelTask(String id) {
        VideoTask task = requireTask(id);
        String status = task.getStatus();
        if ("queued".equals(status)) {
            task.setStatus("canceled");
            task.setFinishedAt(LocalDateTime.now());
            tasks.save(task);
        } else if (ACTIVE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("任务已提交到厂商，无法取消（请求已发出）");
        } else {
            throw new IllegalArgumentException("任务已结束，无需取消");
        }
        return taskView(task);
    }

    @Override
    public Map<String, Object> deleteTask(String id) {
        VideoTask task = requireTask(id);
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        tasks.delete(task);
        String localPath = state.video == null ? "" : Texts.str(state.video, "localPath");
        if (!localPath.isEmpty()) {
            files.unlinkIfUnused(java.nio.file.Paths.get(localPath));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    /**
     * 把一张已生成的图片转成视频输入图。
     *
     * <p><b>不复制文件</b>：直接引用生成结果那一份（同机同盘）。删掉生成记录时由
     * {@code unlinkIfUnused} 数引用决定要不要真删文件，不会把视频输入图搞丢。
     */
    @Override
    public Map<String, Object> imageTaskToVideo(String taskId, Map<String, Object> body) {
        Map<String, Object> safe = body == null ? new LinkedHashMap<String, Object>() : body;
        ImageTask imageTask = imageTasks.findById(taskId).orElseThrow(
                () -> new IllegalArgumentException("生成记录不存在"));
        Map<String, Object> result = readConfig(imageTask.getResultJson());
        List<Map<String, Object>> images = mapList(result.get("images"));
        String imageId = Texts.str(safe, "imageId");
        Map<String, Object> picked = null;
        if (!imageId.isEmpty()) {
            for (Map<String, Object> candidate : images) {
                if (imageId.equals(Texts.str(candidate, "id"))) {
                    picked = candidate;
                    break;
                }
            }
        } else if (!images.isEmpty()) {
            picked = images.get(0);
        }
        if (picked == null) {
            throw new IllegalArgumentException("找不到这张图片");
        }

        String localPathRaw = Texts.str(picked, "localPath");
        if (localPathRaw.isEmpty()) {
            throw new IllegalArgumentException("这张图片的本地文件已被删除，无法送去视频模块");
        }
        Path localPath = java.nio.file.Paths.get(localPathRaw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(localPath)) {
            throw new IllegalArgumentException("这张图片的本地文件已被删除，无法送去视频模块");
        }
        String imageFileName = Texts.str(picked, "fileName");
        String ext = FileStorageService.extensionOf(imageFileName);
        if (ext.isEmpty()) {
            ext = ".png";
        }
        String mime = VideoGenerationClient.INPUT_MIME_BY_EXT.get(ext);
        if (mime == null) {
            mime = "image/png";
        }

        StoredFile stored = null;
        Optional<StoredFile> existing = storedFiles.findByRelativePath(files.relativize(localPath));
        if (existing.isPresent()) {
            stored = existing.get();
        } else {
            stored = files.register(localPath, "generated", imageFileName, mime);
        }
        VideoInput input = new VideoInput(stored.getId(), "fromImage", imageTask.getId());
        inputs.save(input);

        picked.put("sentToVideo", sentToVideo(input.getId()));
        List<Map<String, Object>> refs = mapList(result.get("refs"));
        // 原样保留 result_json 里其它键（比如 refs 之外的历史字段），只把改过的 images 写回去
        Map<String, Object> rewritten = new LinkedHashMap<String, Object>(result);
        rewritten.put("images", images);
        rewritten.put("refs", refs);
        imageTask.setResultJson(writeJson(rewritten));
        imageTasks.save(imageTask);

        return inputRecord(input, stored);
    }

    private Map<String, Object> sentToVideo(String inputId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("inputId", inputId);
        out.put("at", System.currentTimeMillis());
        return out;
    }

    /** 任务视图，字段与键序对齐 Python 版 {@code new_video_task}。 */
    private Map<String, Object> taskView(VideoTask task) {
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        Map<String, Object> params = readConfig(task.getParamsJson());
        String provider = state.provider == null ? "" : state.provider;
        if (provider.isEmpty() && task.getModelId() != null) {
            VideoModel model = models.findById(task.getModelId()).orElse(null);
            if (model != null && model.getProvider() != null) {
                provider = model.getProvider();
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", task.getId());
        out.put("modelId", task.getModelId() == null ? "" : task.getModelId());
        out.put("modelName", task.getModelName());
        out.put("provider", provider);
        out.put("prompt", task.getPrompt());
        out.put("params", params);
        out.put("input", state.input);
        out.put("endInput", state.endInput);
        out.put("status", task.getStatus());
        out.put("remoteTaskId", text(task.getRemoteTaskId()));
        out.put("remoteStatus", state.remoteStatus);
        out.put("nextPollAt", state.nextPollAt);
        out.put("video", state.video);
        out.put("lastFrame", state.lastFrame);
        out.put("error", text(task.getErrorMessage()));
        out.put("progress", state.progress);
        out.put("attempts", task.getAttempts());
        out.put("cancelRequested", state.cancelRequested);
        out.put("createdAt", millis(task.getCreatedAt()));
        out.put("startedAt", state.startedAt);
        out.put("finishedAt", task.getFinishedAt() == null ? null : millis(task.getFinishedAt()));
        return out;
    }

    // ================================================================ 调度

    /**
     * 定时驱动：每 3 秒看一次，挑一条到点的任务推进。串行执行，避免并发打满厂商接口。
     */
    @Override
    @Scheduled(fixedDelayString = "${app.video.scheduler.delay-ms:3000}")
    public void processQueue() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<VideoTask> rows = tasks.findByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES);
            long now = System.currentTimeMillis();
            VideoTask picked = null;
            for (VideoTask row : rows) {
                VideoTaskResult state = VideoTaskResult.read(json, row.getResultJson());
                if (state.nextPollAt <= now) {
                    picked = row;
                    break;
                }
            }
            if (picked == null) {
                return;
            }
            String status = picked.getStatus();
            if ("queued".equals(status)) {
                // 本地豆包同一时间只能跑一个任务，别家厂商没这个限制
                if (doubaoBusyExcept(picked)) {
                    VideoTaskResult state = VideoTaskResult.read(json, picked.getResultJson());
                    state.nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
                    picked.setResultJson(state.write(json));
                    tasks.save(picked);
                    return;
                }
                submitTask(picked);
            } else if ("polling".equals(status)) {
                pollTask(picked);
            } else if ("downloading".equals(status)) {
                downloadTask(picked);
            }
        } catch (Exception err) {
            LOGGER.warn("视频队列处理异常：{}", err.getMessage());
        } finally {
            processing.set(false);
        }
    }

    private boolean doubaoBusyExcept(VideoTask task) {
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        if (!"doubao".equals(state.provider)) {
            return false;
        }
        for (String status : IN_FLIGHT_STATUSES) {
            if ("queued".equals(status)) {
                continue;
            }
            List<VideoTask> rows = tasks.findByStatusOrderByCreatedAtAsc(status);
            for (VideoTask other : rows) {
                if (other.getId().equals(task.getId())) {
                    continue;
                }
                VideoTaskResult otherState = VideoTaskResult.read(json, other.getResultJson());
                if ("doubao".equals(otherState.provider)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void submitTask(VideoTask task) {
        long now = System.currentTimeMillis();
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        task.setStatus("submitting");
        if (state.startedAt == null) {
            state.startedAt = now;
        }
        task.setAttempts(task.getAttempts() + 1);
        task.setErrorMessage("");
        state.nextPollAt = 0;
        task.setResultJson(state.write(json));
        tasks.save(task);

        VideoModel model = task.getModelId() == null ? null : models.findById(task.getModelId()).orElse(null);
        if (model == null) {
            fail(task, "这个任务使用的视频模型已被删除");
            return;
        }
        try {
            Map<String, Object> params = readConfig(task.getParamsJson());
            Map<String, Object> submitted = provider.submit(modelSpec(model), task.getPrompt(), params,
                    inputSpec(state.input), inputSpec(state.endInput));
            String remoteTaskId = Texts.str(submitted, "remoteTaskId");
            if (remoteTaskId.isEmpty()) {
                // remote_task_id 上有唯一索引：空串会和别的任务撞车，必须留 NULL
                failWith(task, VideoTaskResult.read(json, task.getResultJson()),
                        "提交成功但没拿到任务 ID");
                return;
            }
            VideoTaskResult after = VideoTaskResult.read(json, task.getResultJson());
            task.setRemoteTaskId(remoteTaskId);
            after.remoteStatus = "submitted";
            task.setStatus("polling");
            after.nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
            String note = Texts.str(submitted, "note");
            if (!note.isEmpty()) {
                after.progress = Texts.truncate(note, 200);
            }
            task.setErrorMessage("");
            task.setResultJson(after.write(json));
            tasks.save(task);
        } catch (Exception err) {
            fail(task, message(err));
        }
    }

    private void pollTask(VideoTask task) {
        VideoModel model = task.getModelId() == null ? null : models.findById(task.getModelId()).orElse(null);
        if (model == null) {
            fail(task, "这个任务使用的视频模型已被删除");
            return;
        }
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        VideoGenerationClient.VideoResult result;
        try {
            result = provider.query(modelSpec(model), text(task.getRemoteTaskId()));
        } catch (Exception err) {
            int count = state.pollErrors + 1;
            task.setErrorMessage("查询任务状态失败（第 " + count + " 次）：" + message(err));
            state.pollErrors = count;
            if (count >= MAX_POLL_ERRORS) {
                failWith(task, state, "连续 " + count + " 次查询任务状态失败，已放弃：" + message(err));
                return;
            }
            state.nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
            task.setResultJson(state.write(json));
            tasks.save(task);
            return;
        }

        String remoteStatus = !text(result.getRaw()).isEmpty() ? result.getRaw() : text(result.getStatus());
        state.remoteStatus = remoteStatus;
        state.pollErrors = 0;
        task.setErrorMessage("");
        if ("success".equals(result.getStatus())) {
            if (text(result.getVideoUrl()).isEmpty()) {
                failWith(task, state, "厂商报告生成成功，但没有返回视频地址");
                return;
            }
            state.remoteVideoUrl = result.getVideoUrl();
            task.setStatus("downloading");
            state.nextPollAt = 0;
            task.setResultJson(state.write(json));
            tasks.save(task);
            return;
        }
        if ("failed".equals(result.getStatus())) {
            String error = text(result.getError());
            failWith(task, state, error.isEmpty() ? "厂商返回任务失败" : error);
            return;
        }
        // running：进度只对本地豆包显示（别家没有可读的阶段信息）
        if ("doubao".equals(model.getProvider())) {
            long waited = Math.max(0L, (System.currentTimeMillis() - millis(task.getCreatedAt())) / 1000L);
            String human = waited >= 60 ? (waited / 60) + " 分 " + (waited % 60) + " 秒" : waited + " 秒";
            String raw = text(result.getRaw());
            state.progress = (raw.isEmpty() ? "生成中" : raw) + " · 已等 " + human;
        }
        state.nextPollAt = System.currentTimeMillis() + POLL_INTERVAL_MS;
        task.setResultJson(state.write(json));
        tasks.save(task);
    }

    private void downloadTask(VideoTask task) {
        VideoTaskResult state = VideoTaskResult.read(json, task.getResultJson());
        String url = state.remoteVideoUrl;
        byte[] buffer;
        String ext;
        try {
            VideoGenerationClient.VideoDownload download = provider.download(url);
            buffer = download.getBuffer();
            ext = download.getExt() == null || download.getExt().trim().isEmpty() ? ".mp4" : download.getExt();
        } catch (Exception err) {
            fail(task, message(err));
            return;
        }

        StoredFile stored;
        try {
            stored = files.store("videos", buffer, Ids.nextId("video") + ext,
                    FileStorageService.guessMime(ext));
        } catch (Exception err) {
            fail(task, message(err));
            return;
        }
        Path localPath = files.resolve(stored);
        long size = stored.getSizeBytes();
        try {
            size = Files.size(localPath);
        } catch (IOException ignored) {
            // 文件系统读不到大小就退回登记时记的字节数
        }
        Map<String, Object> video = new LinkedHashMap<String, Object>();
        video.put("fileName", stored.getStorageName());
        video.put("localPath", localPath.toString());
        video.put("localUrl", files.localUrlFor(stored));
        video.put("remoteUrl", url == null ? "" : url);
        video.put("size", size);
        state.video = video;
        task.setStatus("success");
        task.setErrorMessage("");
        task.setFinishedAt(LocalDateTime.now());
        task.setResultJson(state.write(json));
        tasks.save(task);

        // 抽末帧失败绝不让任务失败：尾帧只是给画布复用的便利产物
        try {
            state.lastFrame = registerLastFrame(task, localPath, stored.getStorageName());
            task.setResultJson(state.write(json));
            tasks.save(task);
        } catch (Exception err) {
            LOGGER.warn("抽末帧失败 {}：{}", task.getId(), message(err));
        }
    }

    /**
     * 抽出视频最后一帧并登记成一条视频输入图。
     *
     * <p>抽帧靠外部 {@code ffmpeg}（增强引擎目录里的那份）；找不到就直接抛，由调用方记日志。
     */
    private Map<String, Object> registerLastFrame(VideoTask task, Path videoPath, String videoName) {
        Path out = paths.videoInputs().resolve(Ids.nextId("vin") + ".png").normalize();
        paths.ensure(out.getParent());
        extractLastFrame(videoPath, out);
        long size;
        try {
            size = Files.size(out);
        } catch (IOException err) {
            throw new IllegalStateException("无法读取抽出的尾帧：" + err.getMessage(), err);
        }
        if (size < 1024) {
            throw new IllegalStateException("ffmpeg 没抽出最后一帧（可能视频还没写完或格式不认识）");
        }
        String stem = videoName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        String originalName = stem + "-last.png";
        StoredFile stored = files.register(out, "videoInputs", originalName, "image/png");
        VideoInput input = new VideoInput(stored.getId(), "lastFrame", task.getId());
        inputs.save(input);

        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", input.getId());
        snapshot.put("fileName", stored.getStorageName());
        snapshot.put("originalName", originalName);
        snapshot.put("localPath", out.toString());
        snapshot.put("localUrl", files.localUrlFor(stored));
        snapshot.put("mimeType", "image/png");
        snapshot.put("size", size);
        return snapshot;
    }

    /** 先按「从结尾倒数 0.08 秒」抽，失败再按探到的时长做绝对定位抽。 */
    private void extractLastFrame(Path videoPath, Path out) {
        List<String> tail = Arrays.asList("ffmpeg", "-y", "-sseof", "-0.08", "-i", videoPath.toString(),
                "-frames:v", "1", "-q:v", "2", out.toString());
        if (runFfmpeg(tail, 60)) {
            return;
        }
        double duration = probeDuration(videoPath);
        String seek = String.format(java.util.Locale.ROOT, "%.3f", Math.max(0.0, duration - 0.08));
        List<String> fallback = Arrays.asList("ffmpeg", "-y", "-ss", seek, "-i", videoPath.toString(),
                "-frames:v", "1", "-q:v", "2", out.toString());
        if (!runFfmpeg(fallback, 60)) {
            throw new IllegalStateException("ffmpeg 没抽出最后一帧（可能视频还没写完或格式不认识）");
        }
    }

    private boolean runFfmpeg(List<String> command, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            // Java 8 没有 Redirect.DISCARD，手动把输出抽干，否则进程可能因管道写满而卡住
            drainQuietly(process);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException err) {
            throw new IllegalStateException("没找到 ffmpeg（增强引擎目录里的 ffmpeg.exe 不见了）", err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg 执行被中断", err);
        }
    }

    /** 读掉子进程的输出，返回收集到的文本（探测时长时要用）。 */
    private String drainQuietly(Process process) {
        StringBuilder captured = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                captured.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // 流读失败不影响判定结果
        }
        return captured.toString();
    }

    private double probeDuration(Path videoPath) {
        ProcessBuilder builder = new ProcessBuilder("ffmpeg", "-i", videoPath.toString());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String captured = drainQuietly(process);
            process.waitFor(30, TimeUnit.SECONDS);
            Matcher matcher = DURATION_PATTERN.matcher(captured);
            if (matcher.find()) {
                int hours = Integer.parseInt(matcher.group(1));
                int minutes = Integer.parseInt(matcher.group(2));
                double seconds = Double.parseDouble(matcher.group(3));
                return hours * 3600.0 + minutes * 60.0 + seconds;
            }
        } catch (Exception ignored) {
            // 探不到时长就按 0 处理，退化成从开头抽
        }
        return 0.0;
    }

    private void fail(VideoTask task, String message) {
        failWith(task, VideoTaskResult.read(json, task.getResultJson()), message);
    }

    /** 用调用方手里那份（可能刚改过的）状态落失败，避免把 remoteStatus 之类的更新丢掉。 */
    private void failWith(VideoTask task, VideoTaskResult state, String message) {
        task.setStatus("failed");
        task.setErrorMessage(message);
        task.setFinishedAt(LocalDateTime.now());
        task.setResultJson(state.write(json));
        tasks.save(task);
    }

    // ================================================================ 快照转换

    private VideoGenerationClient.ModelSpec modelSpec(VideoModel model) {
        Map<String, Object> config = readConfig(model.getConfigJson());
        String ciphertext = model.getApiKeyCiphertext() == null ? "" : model.getApiKeyCiphertext();
        String apiKey = "";
        if (!ciphertext.isEmpty()) {
            try {
                apiKey = cipher.decrypt(ciphertext);
            } catch (RuntimeException err) {
                throw new IllegalStateException("无法解密这个视频模型的 API Key，请重新填写");
            }
        }
        return new VideoGenerationClient.ModelSpec(model.getName(), model.getProvider(), model.getBaseUrl(),
                apiKey, model.getModelName(), Texts.str(config, "extraParams", ""));
    }

    private VideoGenerationClient.InputSpec inputSpec(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new VideoGenerationClient.InputSpec(
                Texts.str(snapshot, "id"),
                Texts.str(snapshot, "fileName"),
                Texts.str(snapshot, "originalName"),
                Texts.str(snapshot, "localPath"),
                Texts.str(snapshot, "localUrl"),
                Texts.str(snapshot, "mimeType"),
                Texts.asLong(snapshot.get("size"), 0L));
    }

    private Map<String, Object> snapshotOf(VideoInput input) {
        StoredFile stored = findStoredQuietly(input.getFileId());
        if (stored == null) {
            return null;
        }
        return executionSnapshot(input, stored);
    }

    // ================================================================ 小工具

    private VideoModel requireModel(String id) {
        return models.findById(id).orElseThrow(
                () -> new IllegalArgumentException("视频模型不存在"));
    }

    private VideoTask requireTask(String id) {
        return tasks.findById(id).orElseThrow(
                () -> new IllegalArgumentException("视频任务不存在"));
    }

    private StoredFile findStoredQuietly(String fileId) {
        if (fileId == null || fileId.trim().isEmpty()) {
            return null;
        }
        return storedFiles.findById(fileId).orElse(null);
    }

    private String decryptQuietly(String ciphertext) {
        try {
            return cipher.decrypt(ciphertext);
        } catch (RuntimeException err) {
            return "";
        }
    }

    /** 与 Python 版 {@code mask_key} 一致：8 位以内只显示星号，长串保留首尾各 4 位。 */
    private String maskKey(String key) {
        String value = key == null ? "" : key;
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String paramText(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("defaultDuration", "");
        config.put("defaultResolution", "");
        config.put("defaultRatio", "");
        config.put("extraParams", "");
        return config;
    }

    private Map<String, Object> readConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
        } catch (Exception err) {
            LOGGER.warn("JSON 解析失败：{}", err.getMessage());
            return new LinkedHashMap<String, Object>();
        }
    }

    private String writeConfig(Map<String, Object> config) {
        return writeJson(config);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception err) {
            throw new IllegalStateException("无法序列化视频数据：" + err.getMessage(), err);
        }
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

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String message(Throwable err) {
        String text = err.getMessage();
        return text == null || text.trim().isEmpty() ? err.getClass().getSimpleName() : text;
    }

    private long millis(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String fmtMb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    /** 供状态接口复用：服务商出厂参数。 */
    public static Map<String, Object> providerDefaults() {
        return VideoGenerationClient.presets();
    }

    /** 供状态接口复用：当前可用的状态集合。 */
    public static Collection<String> activeStatuses() {
        return ACTIVE_STATUSES;
    }
}

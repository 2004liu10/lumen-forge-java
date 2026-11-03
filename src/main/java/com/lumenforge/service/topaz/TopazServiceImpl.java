package com.lumenforge.service.topaz;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.entity.task.GenerationTask;
import com.lumenforge.entity.topaz.TopazJob;
import com.lumenforge.entity.video.VideoTask;
import com.lumenforge.mapper.task.GenerationTaskMapper;
import com.lumenforge.mapper.topaz.TopazJobMapper;
import com.lumenforge.mapper.video.VideoTaskMapper;
import com.lumenforge.storage.StoragePaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Topaz 增强模块的业务实现。
 *
 * <p>Python 版把「引擎探测 / 档位配置 / 本地文件夹扫描 / 候选清单」做完了，
 * 真正调 ffmpeg 跑 tvai_fi + tvai_up 的流水线明确留空壳。这里照搬同样的范围：
 * 四条执行端点如实回「本期未实现」，不伪造排队状态。
 *
 * <p>有一处必须说明的结构差异：Python 版用一张单例表 {@code topaz_local}
 * 存「本地素材目录 / 当前质感档 / 本地提升记录」，但 Java 版的 DDL 里没有这张表，
 * 而且 V1 迁移脚本是 Flyway 基准、不能改。所以这三个键落到数据根目录下的
 * {@code topaz-local.json}，键名保持 {@code dir} / {@code look} / {@code results}，
 * 语义与 Python 一致（重启后仍在，改目录不需要重新配置）。
 */
@Service
public class TopazServiceImpl implements TopazService {

    /** 本地文件夹一次最多列这么多条：每条在弹窗里都是一个 {@code <video>}，太多会同时开一堆解码器。 */
    private static final int MAX_LOCAL_FILES = 60;

    /** 子目录最多往下找这么深。 */
    private static final int LOCAL_WALK_DEPTH = 4;

    /** 识别「提升成品」的文件名：{@code xxx-topaz.mp4} / {@code xxx-topaz-1a2b3c.mp4}。 */
    private static final String TOPAZ_OUT_NAME_RE = "-topaz(?:-[0-9a-f]{6})?\\.mp4$";

    /** 认哪些扩展名算视频；生成结果和本地文件夹两条路共用同一份标准。 */
    private static final List<String> VIDEO_EXTS = Arrays.asList(
            ".mp4", ".mov", ".mkv", ".webm", ".avi", ".flv", ".m4v", ".wmv", ".mpeg", ".mpg");

    /** 候选清单里「待提升」最多回这么多条。 */
    private static final int PENDING_LIMIT = 200;

    /** 候选清单里「已提升」最多回这么多条。 */
    private static final int DONE_LIMIT = 100;

    /** 执行流水线本期未实现的统一说明，原文照抄 Python 版 {@code _NOT_READY}。 */
    private static final String NOT_READY =
            "提升画质的执行流水线本期未实现（按「骨架 + 核心链路」的范围决策，Topaz 只保留引擎探测、档位配置与候选清单）。"
                    + "需要时补齐 tvai_fi + tvai_up + hevc_nvenc 那一套。";

    private static final String LOCAL_CONFIG_FILE = "topaz-local.json";

    private final ObjectMapper objectMapper;
    private final StoragePaths paths;
    private final TopazJobMapper jobMapper;
    private final GenerationTaskMapper taskMapper;
    private final VideoTaskMapper videoTaskMapper;

    public TopazServiceImpl(ObjectMapper objectMapper,
                            StoragePaths paths,
                            TopazJobMapper jobMapper,
                            GenerationTaskMapper taskMapper,
                            VideoTaskMapper videoTaskMapper) {
        this.objectMapper = objectMapper;
        this.paths = paths;
        this.jobMapper = jobMapper;
        this.taskMapper = taskMapper;
        this.videoTaskMapper = videoTaskMapper;
    }

    // ============================================================ 候选清单

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> candidates() {
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> done = new ArrayList<Map<String, Object>>();
        // 画布 / 视频生成记录里出现过的成品路径：本地文件夹扫描时跳过，
        // 否则同一条片子会以「自用」和「本地」两个身份各出现一次。
        Set<String> claimed = new HashSet<String>();

        // ① 自用模块（RunningHub 画布）的多结果
        List<GenerationTask> tasks = taskMapper.findAll();
        tasks.sort(new java.util.Comparator<GenerationTask>() {
            @Override
            public int compare(GenerationTask left, GenerationTask right) {
                long a = left.getCreatedAt() == null ? 0L : left.getCreatedAt();
                long b = right.getCreatedAt() == null ? 0L : right.getCreatedAt();
                return b < a ? -1 : (b > a ? 1 : 0);
            }
        });
        for (GenerationTask task : tasks) {
            List<Map<String, Object>> outputs = readList(task.getOutputsJson());
            for (int index = 0; index < outputs.size(); index++) {
                Map<String, Object> out = outputs.get(index);
                if (out == null || out.isEmpty()) {
                    continue;
                }
                Map<String, Object> base = new LinkedHashMap<String, Object>();
                base.put("source", "task");
                base.put("taskId", task.getId());
                base.put("index", index);
                base.put("canvasId", text(task.getCanvasId()));
                base.put("canvasName", text(task.getCanvasName()).isEmpty() ? "（未知画布）" : text(task.getCanvasName()));
                base.put("accountName", text(task.getAccountName()));
                base.put("createdAt", task.getCreatedAt() == null ? 0L : task.getCreatedAt());
                String localPath = text(out.get("localPath"));
                base.put("fileName", localPath.isEmpty() ? "" : fileName(localPath));
                base.put("url", text(out.get("localUrl")));
                base.put("size", sizeOf(out.get("size")));
                push(pending, done, claimed, base, out);
            }
        }

        // ② 视频生成模块（图生视频）的成品 —— 一条任务一个视频
        List<VideoTask> videoTasks = videoTaskMapper.findAll();
        videoTasks.sort(new java.util.Comparator<VideoTask>() {
            @Override
            public int compare(VideoTask left, VideoTask right) {
                long a = millis(left.getCreatedAt());
                long b = millis(right.getCreatedAt());
                return b < a ? -1 : (b > a ? 1 : 0);
            }
        });
        for (VideoTask task : videoTasks) {
            Map<String, Object> video = readVideo(task.getResultJson());
            Map<String, Object> base = new LinkedHashMap<String, Object>();
            base.put("source", "video");
            base.put("taskId", task.getId());
            base.put("index", 0);
            base.put("canvasId", "");
            String modelName = text(task.getModelName());
            base.put("canvasName", modelName.isEmpty() ? "（视频生成）" : modelName);
            base.put("accountName", "");
            base.put("createdAt", task.getFinishedAt() == null
                    ? millis(task.getCreatedAt()) : millis(task.getFinishedAt()));
            String localPath = video == null ? "" : text(video.get("localPath"));
            base.put("fileName", localPath.isEmpty() ? "" : fileName(localPath));
            base.put("url", video == null ? "" : text(video.get("localUrl")));
            base.put("size", video == null ? 0L : sizeOf(video.get("size")));
            // 给界面显示：让用户知道这条是哪来的
            base.put("originLabel", "视频生成");
            String prompt = text(task.getPrompt());
            base.put("desc", prompt.length() > 60 ? prompt.substring(0, 60) : prompt);
            push(pending, done, claimed, base, video);
        }

        // ③ 用户指定的本地文件夹里的视频（弹窗里贴路径「读取」出来的）
        String localDir = getLocalDir();
        if (!localDir.isEmpty()) {
            List<Map<String, Object>> items = scanItems(localDir);
            for (Map<String, Object> item : items) {
                String name = text(item.get("name"));
                // 提升成品（文件名带 -topaz）不进候选：它已经是结果了，列进「待提升」只会被二次提升。
                if (isTopazOutName(name)) {
                    continue;
                }
                String itemPath = text(item.get("path"));
                // 这条文件已经被画布 / 视频生成的任务记录占了，上面 ①② 已经列过一次。
                if (claimed.contains(pathKey(itemPath))) {
                    continue;
                }
                Map<String, Object> base = new LinkedHashMap<String, Object>();
                base.put("source", "local");
                base.put("taskId", "");
                base.put("index", 0);
                base.put("canvasId", "");
                base.put("canvasName", "本地文件");
                base.put("accountName", "");
                base.put("createdAt", item.get("mtime"));
                base.put("fileName", name);
                base.put("url", item.get("url")); // 预览流地址，前端当缩略图用
                base.put("size", item.get("size"));
                base.put("localPath", itemPath);
                base.put("originLabel", "本地");
                Map<String, Object> rec = localResultFor(itemPath);
                if (rec == null) {
                    rec = adoptLocalResult(itemPath);
                }
                if (rec != null) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>(base);
                    row.put("tier", rec.get("tier"));
                    row.put("tierLabel", rec.get("tierLabel"));
                    row.put("outWidth", rec.get("outWidth"));
                    row.put("outHeight", rec.get("outHeight"));
                    row.put("at", rec.get("at"));
                    done.add(row);
                } else {
                    pending.add(base);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("pending", pending.size() > PENDING_LIMIT ? pending.subList(0, PENDING_LIMIT) : pending);
        out.put("done", done.size() > DONE_LIMIT ? done.subList(0, DONE_LIMIT) : done);
        return out;
    }

    /**
     * 把一条结果登记进候选清单。
     *
     * <p>认领路径必须放在「是不是视频」的判断之前：成品被挪走 / 清理后文件已不存在，
     * 但「这条已经提升过」的事实还在库里 —— 原片路径照样要认领，
     * 否则本地扫描会把原片重新列成「待提升」。
     */
    private void push(List<Map<String, Object>> pending,
                      List<Map<String, Object>> done,
                      Set<String> claimed,
                      Map<String, Object> base,
                      Map<String, Object> out) {
        if (out != null) {
            String localPath = text(out.get("localPath"));
            if (!localPath.isEmpty()) {
                claimed.add(pathKey(localPath));
            }
            Map<String, Object> topaz = asMap(out.get("topaz"));
            if (topaz != null) {
                String sourcePath = text(topaz.get("sourcePath"));
                if (!sourcePath.isEmpty()) {
                    claimed.add(pathKey(sourcePath));
                }
            }
        }
        if (!isVideoOut(out)) {
            return;
        }
        Map<String, Object> topaz = asMap(out.get("topaz"));
        if (topaz != null && topaz.get("at") != null) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(base);
            row.put("tier", topaz.get("tier"));
            row.put("tierLabel", topaz.get("tierLabel"));
            row.put("outWidth", topaz.get("outWidth"));
            row.put("outHeight", topaz.get("outHeight"));
            row.put("at", topaz.get("at"));
            done.add(row);
        } else {
            pending.add(base);
        }
    }

    /** 一条结果是不是「视频」，且文件还在本地。 */
    private boolean isVideoOut(Map<String, Object> out) {
        if (out == null) {
            return false;
        }
        String localPath = text(out.get("localPath"));
        if (localPath.isEmpty()) {
            return false;
        }
        String fileType = text(out.get("fileType")).toLowerCase(Locale.ROOT);
        boolean video = fileType.equals("mp4") || fileType.equals("video") || isVideoPath(localPath);
        if (!video) {
            return false;
        }
        return Files.exists(Paths.get(localPath));
    }

    /** 查这个本地文件提升过没有（本地没有 task 可回写，只能自己记一份）。 */
    private Map<String, Object> localResultFor(String path) {
        for (Map<String, Object> rec : localResults()) {
            if (path.equals(text(rec.get("sourcePath")))) {
                return rec;
            }
        }
        return null;
    }

    /**
     * 原片被挪过位置时的补救：提升记录里存的是老路径，文件挪走后按路径就永远对不上。
     *
     * <p>这里按文件名过继：老记录的原片确实已经不在磁盘上 + 文件名完全一致才认，
     * 名字还在的老文件一律不动（宁可不认，也不能把没提升过的当成提升过）。
     */
    private Map<String, Object> adoptLocalResult(String path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        for (Map<String, Object> rec : localResults()) {
            String source = text(rec.get("sourcePath"));
            if (source.isEmpty()) {
                continue;
            }
            if (pathKey(source).equals(pathKey(path))) {
                continue;
            }
            if (Files.exists(Paths.get(source))) {
                continue;
            }
            if (fileName(source).toLowerCase(Locale.ROOT).equals(name)) {
                return rec;
            }
        }
        return null;
    }

    private List<Map<String, Object>> localResults() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Object raw = readLocalConfig().get("results");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Map<String, Object> row = asMap(item);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    // ============================================================ 执行流水线（本期空壳）

    @Override
    public Map<String, Object> enhance(Map<String, Object> body) {
        throw new IllegalArgumentException(NOT_READY);
    }

    @Override
    public Map<String, Object> enhanceBatch(Map<String, Object> body) {
        Object items = body == null ? null : body.get("items");
        if (!(items instanceof List) || ((List<?>) items).isEmpty()) {
            throw new IllegalArgumentException("没有勾选任何视频");
        }
        throw new IllegalArgumentException(NOT_READY);
    }

    @Override
    public Map<String, Object> cancel(Map<String, Object> body) {
        throw new IllegalArgumentException(NOT_READY);
    }

    @Override
    public Map<String, Object> restore(Map<String, Object> body) {
        throw new IllegalArgumentException(NOT_READY);
    }

    @Override
    @Transactional
    public Map<String, Object> clearFailed() {
        // 只清终态失败 / 取消：排队中、正在跑的不动；成功的那条已经写进结果本身，
        // 跟这份记录没关系，清掉也不会丢成品。
        Collection<String> dead = Arrays.asList("failed", "canceled");
        List<TopazJob> doomed = jobMapper.findByStatusInOrderByCreatedAtAsc(dead);
        int removed = doomed.size();
        if (removed > 0) {
            jobMapper.deleteAll(doomed);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("removed", removed);
        return out;
    }

    // ============================================================ 状态快照（GET /api/state 的 topaz 键）

    /**
     * 等价于 Python 的 {@code topaz_snapshot()}：引擎探测结果 + 档位/质感档清单 + 本地目录 + 任务快照。
     *
     * <p>键名与 Python 逐字符一致，前端 {@code /api/state} 直接照搬渲染，不需要再拼一层。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Map<String, Object> probe = resolveTopaz();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("found", probe.get("found"));
        // 引擎是随包内置的，还是回落到本机另外装的 Topaz
        out.put("bundled", Boolean.TRUE.equals(probe.get("bundled")));
        out.put("ffmpeg", probe.get("ffmpeg"));
        out.put("dir", probe.get("dir"));
        out.put("modelsReady", probe.get("modelsReady"));
        out.put("tiers", brief(tiers()));
        // 质感档：默认「不增亮」（压皮肤高光），前端按这个渲染选择行
        out.put("look", getLook().get("id"));
        out.put("looks", brief(looks()));
        // 本地文件夹：弹窗里那个输入框靠它回显；一次最多列多少条也给前端，好在界面上说明
        out.put("localDir", getLocalDir());
        out.put("localLimit", MAX_LOCAL_FILES);
        out.put("jobs", jobsSnapshot());
        return out;
    }

    /** 档位 / 质感档回给前端时只留 id、label、desc 三个字段（filter 是给 ffmpeg 用的，不下发）。 */
    private List<Map<String, Object>> brief(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.get("id"));
            item.put("label", row.get("label"));
            item.put("desc", row.get("desc"));
            out.add(item);
        }
        return out;
    }

    /** 当前质感档；没配过或配了个已经不存在的 id 时回默认第一档（与 Python {@code get_look()} 一致）。 */
    private Map<String, Object> getLook() {
        String wanted = text(readLocalConfig().get("look")).trim();
        List<Map<String, Object>> looks = looks();
        for (Map<String, Object> row : looks) {
            if (text(row.get("id")).equals(wanted)) {
                return row;
            }
        }
        return looks.get(0);
    }

    /** 前 30 条任务记录，最新的排前面。 */
    private List<Map<String, Object>> jobsSnapshot() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<TopazJob> jobs = jobMapper.findTop30ByOrderByCreatedAtDesc();
        if (jobs == null) {
            return rows;
        }
        for (TopazJob job : jobs) {
            rows.add(publicJob(job));
        }
        return rows;
    }

    /**
     * 与 Python {@code _public_job()} 同形。
     *
     * <p>DDL 只给了「源文件 / 结果文件 / 状态 / 档位 / 进度 / 错误」这些列，
     * Python 里那套 {@code source / taskId / index / tierLabel / fileName / speed / message / outUrl}
     * 是运行时字段，Java 侧统一收在 {@code config_json} 里（本期没有写入路径，所以正常是空表）。
     */
    private Map<String, Object> publicJob(TopazJob job) {
        Map<String, Object> cfg = read(job.getConfigJson());
        if (cfg == null) {
            cfg = new LinkedHashMap<String, Object>();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", job.getId());
        // source 必须带上：恢复时要读 `source || 'task'`，漏了的话重启后
        // video / local 来源的任务全被当成 task，去重和按来源取消就都找不到了
        out.put("source", cfg.get("source"));
        out.put("taskId", cfg.get("taskId"));
        out.put("index", cfg.get("index"));
        out.put("tier", job.getPreset());
        out.put("tierLabel", cfg.get("tierLabel"));
        out.put("fileName", cfg.get("fileName"));
        out.put("status", job.getStatus());
        out.put("progress", progressOf(job.getProgress()));
        out.put("speed", cfg.get("speed"));
        out.put("message", cfg.get("message"));
        out.put("error", job.getErrorMessage());
        Object startedAt = job.getCreatedAt() == null ? null : Long.valueOf(millis(job.getCreatedAt()));
        Object finishedAt = job.getFinishedAt() == null ? null : Long.valueOf(millis(job.getFinishedAt()));
        out.put("startedAt", startedAt);
        out.put("finishedAt", finishedAt);
        out.put("outUrl", cfg.get("outUrl"));
        out.put("sourceUrl", cfg.get("sourceUrl"));
        return out;
    }

    /**
     * Python 的进度是 0-1 的比值；DDL 的 {@code progress} 是 DECIMAL(5,2) 百分比。
     * 这里统一回 0-1（超过 1 的按百分比折算），并做一次 clamp。
     */
    private Object progressOf(BigDecimal value) {
        double raw = value == null ? 0D : value.doubleValue();
        if (raw > 1D) {
            raw = raw / 100D;
        }
        if (raw < 0D) {
            raw = 0D;
        }
        if (raw > 1D) {
            raw = 1D;
        }
        return Double.valueOf(raw);
    }

    // ============================================================ 配置

    @Override
    public Map<String, Object> config(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("dir");
        String dir = raw == null ? "" : String.valueOf(raw).trim();
        Map<String, Object> cfg = readLocalConfig();
        cfg.put("dir", dir);
        writeLocalConfig(cfg);
        // Python 版 set_topaz_dir 返回的是重新探测的结果（不是扫描结果）
        return resolveTopaz();
    }

    @Override
    public Map<String, Object> look(Map<String, Object> body) {
        String wanted = body == null || body.get("look") == null ? "" : String.valueOf(body.get("look"));
        Map<String, Object> chosen = null;
        for (Map<String, Object> look : looks()) {
            if (text(look.get("id")).equals(wanted)) {
                chosen = look;
                break;
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException("未知的质感档");
        }
        Map<String, Object> cfg = readLocalConfig();
        cfg.put("look", chosen.get("id"));
        writeLocalConfig(cfg);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("look", chosen.get("id"));
        out.put("label", chosen.get("label"));
        return out;
    }

    // ============================================================ 本地文件夹

    @Override
    public Map<String, Object> local(Map<String, Object> body) {
        if (body == null || !body.containsKey("dir")) {
            String directory = getLocalDir();
            Map<String, Object> out = scanLocalDir(directory);
            out.put("dir", directory);
            return out;
        }
        Object raw = body.get("dir");
        String directory = raw == null ? "" : String.valueOf(raw).trim();
        Map<String, Object> cfg = readLocalConfig();
        cfg.put("dir", directory);
        writeLocalConfig(cfg);
        return scanLocalDir(directory);
    }

    private Map<String, Object> scanLocalDir(String directory) {
        String root = directory == null ? "" : directory.trim();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dir", root);
        out.put("items", new ArrayList<Map<String, Object>>());
        out.put("error", "");
        if (root.isEmpty()) {
            return out;
        }
        Path rootPath = Paths.get(root);
        if (!Files.isDirectory(rootPath)) {
            out.put("error", "这个目录不存在，检查一下路径");
            return out;
        }
        List<Map<String, Object>> found = new ArrayList<Map<String, Object>>();
        walk(rootPath, 0, found);
        found.sort(new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long a = number(left.get("mtime"));
                long b = number(right.get("mtime"));
                return b < a ? -1 : (b > a ? 1 : 0);
            }
        });
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : found) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(item);
            row.put("url", localPreviewUrl(text(item.get("path"))));
            items.add(row);
        }
        out.put("items", items);
        return out;
    }

    /** 与 {@link #scanLocalDir} 相同的遍历，但只回条目本身（候选清单用）。 */
    private List<Map<String, Object>> scanItems(String directory) {
        Object items = scanLocalDir(directory).get("items");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (items instanceof List) {
            for (Object item : (List<?>) items) {
                Map<String, Object> row = asMap(item);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private void walk(Path current, int depth, List<Map<String, Object>> found) {
        if (found.size() >= MAX_LOCAL_FILES || depth > LOCAL_WALK_DEPTH) {
            return;
        }
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException | RuntimeException ignore) {
            return; // 没权限的子目录直接跳过，别整个扫描失败
        }
        for (Path entry : entries) {
            if (found.size() >= MAX_LOCAL_FILES) {
                return;
            }
            try {
                if (Files.isDirectory(entry)) {
                    walk(entry, depth + 1, found);
                    continue;
                }
            } catch (RuntimeException ignore) {
                continue;
            }
            String name = entry.getFileName().toString();
            if (!isVideoPath(name)) {
                continue;
            }
            try {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("path", entry.toString());
                row.put("name", name);
                row.put("size", Files.size(entry));
                row.put("mtime", Files.getLastModifiedTime(entry).toMillis());
                found.add(row);
            } catch (IOException | RuntimeException ignore) {
                continue;
            }
        }
    }

    @Override
    public Path resolveLocalVideo(String rawPath) {
        String raw = rawPath == null ? "" : rawPath.trim();
        if (raw.isEmpty() || !Paths.get(raw).isAbsolute() || !isVideoPath(raw)) {
            return null;
        }
        Path candidate;
        try {
            candidate = Paths.get(raw).toRealPath();
        } catch (IOException | RuntimeException error) {
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        for (Path root : allowedRoots()) {
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public String videoMime(String rawPath) {
        String lower = rawPath == null ? "" : rawPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        if (lower.endsWith(".flv")) {
            return "video/x-flv";
        }
        if (lower.endsWith(".wmv")) {
            return "video/x-ms-wmv";
        }
        if (lower.endsWith(".mpeg") || lower.endsWith(".mpg")) {
            return "video/mpeg";
        }
        return "application/octet-stream";
    }

    @Override
    public List<Path> allowedRoots() {
        // 只服务「用户自己在提升画质里选过的文件夹」和本程序自己管理的媒体目录
        List<Path> roots = new ArrayList<Path>();
        String localDir = getLocalDir();
        List<String> raw = new ArrayList<String>();
        raw.add(localDir);
        raw.add(paths.outputs().toString());
        raw.add(paths.topaz().toString());
        raw.add(paths.videos().toString());
        raw.add(paths.root().toString());
        for (String item : raw) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            try {
                roots.add(Paths.get(item).toRealPath());
            } catch (IOException | RuntimeException error) {
                try {
                    roots.add(Paths.get(item).toAbsolutePath().normalize());
                } catch (RuntimeException ignore) {
                    continue;
                }
            }
        }
        return roots;
    }

    // ============================================================ 档位 / 质感档定义

    private List<Map<String, Object>> looks() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(look("no-lift", "不增亮（默认 · 压高光，防脸白）",
                "只压掉最亮的那一档（皮肤高光、额头反光），暗部和中调一点不动；再微降饱和 + 加极细颗粒，让人脸不再越提升越白、越光",
                // 拐点 160：<=160 的像素原样输出（暗部、普通肤色完全不动），超过 160 的部分按 0.8 收缩
                "lutyuv=y='if(gt(val,160),160+(val-160)*0.8,val)':u=val:v=val,"
                        + "hue=s=0.98,"
                        + "noise=alls=2:allf=t+u"));
        rows.add(look("natural", "自然（只提升清晰，不改色调）",
                "提升后不做任何调色，亮暗、颜色和原片保持一致，只是变清晰了", ""));
        rows.add(look("soft", "柔和（微降饱和 · 细颗粒）",
                "不改变亮暗，只降一点点饱和、加极细颗粒，减弱 AI 的「塑料光感」",
                "hue=s=0.96,noise=alls=2:allf=t+u"));
        rows.add(look("bright", "明亮通透（提亮 · 更干净）",
                "适合本身就偏暗的素材：提亮中间调、略加饱和（暗素材慎用，会压得更亮）",
                "lutyuv=y='16+pow(clip((val-16)/219,0,1),0.96)*219':u=val:v=val,hue=s=1.03"));
        return rows;
    }

    private Map<String, Object> look(String id, String label, String desc, String filter) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("label", label);
        row.put("desc", desc);
        row.put("filter", filter);
        return row;
    }

    private List<Map<String, Object>> tiers() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", "std");
        row.put("label", "提升画质（Proteus 2× · 60fps）");
        row.put("desc", "Chronos（标准版）补帧到 60fps + Proteus 放大 2×（1080p → 4K；恢复压缩 80 / 恢复细节 60 / 锐化 10），"
                + "不开运动模糊脱模，最后 H265 / NVIDIA 出片。参数与 Topaz GUI 验证过的一套一致。");
        rows.add(row);
        return rows;
    }

    // ============================================================ 引擎探测

    /**
     * 探测 Topaz Video AI 自带的 {@code ffmpeg.exe}。
     *
     * <p>原版不直接调 GUI，而是用它暴露的 {@code tvai_up} 滤镜离线跑本地模型。
     * 顺序：先看随包内置的引擎，再按「用户配的目录 → D 盘 → C 盘 → E 盘 → Program Files」逐个找。
     */
    private Map<String, Object> resolveTopaz() {
        String custom = getLocalDir();
        if (custom.isEmpty()) {
            String env = System.getenv("TOPAZ_DIR");
            custom = env == null ? "" : env.trim();
        }

        // ① 随包内置
        Path bundledRoot = enhancerDir();
        String ffmpeg = findFfmpeg(Arrays.asList(bundledRoot.resolve("tvai"), bundledRoot));
        boolean bundled = !ffmpeg.isEmpty();
        String directory = "";

        // ② 本机装的 Topaz
        if (ffmpeg.isEmpty()) {
            List<String> roots = new ArrayList<String>();
            roots.add(custom);
            roots.add("D:\\");
            roots.add("C:\\");
            roots.add("E:\\");
            roots.add("C:\\Program Files");
            roots.add("C:\\Program Files (x86)");
            for (String root : roots) {
                if (root == null || root.isEmpty()) {
                    continue;
                }
                List<Path> dirs = new ArrayList<Path>();
                dirs.add(Paths.get(root));
                dirs.addAll(topazDirsUnder(Paths.get(root)));
                ffmpeg = findFfmpeg(dirs);
                if (!ffmpeg.isEmpty()) {
                    break;
                }
            }
        }
        if (!ffmpeg.isEmpty()) {
            directory = Paths.get(ffmpeg).getParent().toString();
        }

        Path modelsDir = findModelsDir();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("found", !ffmpeg.isEmpty());
        out.put("bundled", bundled);
        out.put("ffmpeg", ffmpeg);
        out.put("dir", directory);
        out.put("models", modelsDir.toString());
        out.put("modelsReady", Files.exists(modelsDir.resolve("tvai.tz")));
        return out;
    }

    /** 随包内置引擎目录：{@code build/enhancer} 或 {@code resources/enhancer}。 */
    private Path enhancerDir() {
        Path build = Paths.get("build", "enhancer").toAbsolutePath();
        Path resources = Paths.get("resources", "enhancer").toAbsolutePath();
        if (Files.exists(build.resolve("tvai").resolve("ffmpeg.exe"))) {
            return build;
        }
        if (Files.exists(resources.resolve("tvai").resolve("ffmpeg.exe"))) {
            return resources;
        }
        return build;
    }

    /** 某个目录下名字像「topaz video ai」的子目录，版本号大的排前面。 */
    private List<Path> topazDirsUnder(Path root) {
        List<Path> found = new ArrayList<Path>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException | RuntimeException ignore) {
            return found;
        }
        for (Path entry : entries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            String name = entry.getFileName().toString();
            if (name.matches("(?i).*topaz\\s*video\\s*ai.*")) {
                found.add(entry);
            }
        }
        found.sort(new java.util.Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return right.getFileName().toString().compareTo(left.getFileName().toString());
            }
        });
        return found;
    }

    /** 每个目录先试 {@code ffmpeg.exe} 再试 {@code ffmpeg}，返回第一个命中的完整路径。 */
    private String findFfmpeg(List<Path> dirs) {
        for (Path dir : dirs) {
            if (dir == null) {
                continue;
            }
            for (String name : Arrays.asList("ffmpeg.exe", "ffmpeg")) {
                try {
                    Path exe = dir.resolve(name);
                    if (Files.isRegularFile(exe)) {
                        return exe.toString();
                    }
                } catch (RuntimeException ignore) {
                    continue;
                }
            }
        }
        return "";
    }

    /** 模型目录候选：内置 models → ProgramData → LOCALAPPDATA；判据 {@code tvai.tz} 或 {@code ahq-12.json}。 */
    private Path findModelsDir() {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(enhancerDir().resolve("models"));
        String programData = System.getenv("ProgramData");
        candidates.add(Paths.get(programData == null || programData.isEmpty() ? "C:\\ProgramData" : programData,
                "Topaz Labs LLC", "Topaz Video AI", "models"));
        String localAppData = System.getenv("LOCALAPPDATA");
        candidates.add(Paths.get(localAppData == null || localAppData.isEmpty() ? "C:\\Users" : localAppData,
                "Topaz Labs LLC", "Topaz Video AI", "models"));
        for (Path dir : candidates) {
            if (Files.exists(dir.resolve("tvai.tz")) || Files.exists(dir.resolve("ahq-12.json"))) {
                return dir;
            }
        }
        return candidates.get(0);
    }

    // ============================================================ 本地配置（topaz-local.json）

    private Path localConfigFile() {
        return paths.root().resolve(LOCAL_CONFIG_FILE);
    }

    private String getLocalDir() {
        Object raw = readLocalConfig().get("dir");
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private Map<String, Object> readLocalConfig() {
        Path file = localConfigFile();
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    file.toFile(), new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
        } catch (IOException error) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private void writeLocalConfig(Map<String, Object> cfg) {
        Path file = localConfigFile();
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), cfg);
        } catch (IOException error) {
            throw new IllegalArgumentException("保存 Topaz 配置失败：" + error.getMessage());
        }
    }

    // ============================================================ 小工具

    /** 路径比对键：Windows 大小写不敏感，统一小写；其它平台原样。 */
    private String pathKey(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String resolved;
        try {
            resolved = Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException error) {
            resolved = path;
        }
        return isWindows() ? resolved.toLowerCase(Locale.ROOT) : resolved;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isVideoPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTopazOutName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).matches(".*-topaz(?:-[0-9a-f]{6})?\\.mp4$");
    }

    /** 本地视频给前端的预览地址（带 Range 流式返回，{@code <video>} 才能拖动 / 取首帧）。 */
    private String localPreviewUrl(String path) {
        return "/api/topaz/local-file?path=" + percentEncode(path);
    }

    /**
     * 等价于 Python 的 {@code urllib.parse.quote(value, safe='')}：
     * 只保留字母数字与 {@code _ . - ~}，其余（含 {@code /}、空格、中文）一律百分号编码。
     */
    private String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            bytes = value.getBytes();
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        try {
            Path name = Paths.get(path).getFileName();
            return name == null ? "" : name.toString();
        } catch (RuntimeException error) {
            int cut = path.lastIndexOf('/');
            int cutWin = path.lastIndexOf('\\');
            int at = Math.max(cut, cutWin);
            return at >= 0 ? path.substring(at + 1) : path;
        }
    }

    private long millis(LocalDateTime at) {
        if (at == null) {
            return 0L;
        }
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private long sizeOf(Object value) {
        return number(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    private Map<String, Object> readVideo(String json) {
        Map<String, Object> parsed = read(json);
        if (parsed == null) {
            return null;
        }
        return asMap(parsed.get("video"));
    }

    private Map<String, Object> read(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            return null;
        }
    }

    private List<Map<String, Object>> readList(String json) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (json == null || json.isEmpty()) {
            return rows;
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});
            return parsed == null ? rows : parsed;
        } catch (IOException error) {
            return rows;
        }
    }
}

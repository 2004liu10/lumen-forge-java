package com.lumenforge.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控磁盘目录解析。
 *
 * <p>对应 Python 版 {@code app/paths.py}：所有落盘产物都挂在 {@code app.storage.root} 之下，
 * 每个分类（uploads / outputs / generated / refs / videos / video-inputs / materials / topaz）
 * 都有独立子目录，并且允许用户把某个分类改到别的盘上（{@code POST /api/storage}）。
 *
 * <p>自定义目录存在 {@code <root>/storage-paths.json}，重启后仍然生效；
 * {@code refs} 默认跟随 {@code generated}，这样把图片结果改到大盘上时参考图会一起搬过去。
 */
@Component
public class StoragePaths {

    /** 分类键 → 中文说明，顺序即接口返回顺序。 */
    private static final Map<String, String[]> SPECS = new LinkedHashMap<String, String[]>();

    static {
        SPECS.put("uploads", new String[]{"画布素材", "画布上传的图片、视频和音频", "uploads"});
        SPECS.put("outputs", new String[]{"自用模块生成结果", "RunningHub 任务下载的输出文件", "outputs"});
        SPECS.put("topaz", new String[]{"Topaz 增强结果", "Topaz 视频增强输出目录", "topaz"});
        SPECS.put("generated", new String[]{"图片生成结果", "文生图 / 图生图产出的图片", "generated"});
        SPECS.put("refs", new String[]{"参考图", "图生图参考图库", "generated/refs"});
        SPECS.put("videos", new String[]{"视频生成结果", "视频模型产出的视频", "videos"});
        SPECS.put("videoInputs", new String[]{"视频输入图", "视频首帧 / 尾帧上传图", "video-inputs"});
        SPECS.put("materials", new String[]{"素材下载", "素材库下载到本地的文件", "materials"});
    }

    private static final String STATE_FILE = "storage-paths.json";

    private final Path root;
    private final Path stateFile;

    public StoragePaths(@Value("${app.storage.root:./data}") String storageRoot) {
        this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.stateFile = this.root.resolve(STATE_FILE);
        ensureDirectory(this.root);
    }

    /** 数据根目录（绝对路径、已归一化）。 */
    public Path root() {
        return root;
    }

    /** 解析某个分类的目录；自定义过的返回自定义路径。 */
    public Path dir(String key) {
        String custom = customDirs().get(key);
        if (custom != null && !custom.isEmpty()) {
            Path path = Paths.get(custom).toAbsolutePath().normalize();
            ensureDirectory(path);
            return path;
        }
        return defaultDir(key);
    }

    public Path uploads() { return dir("uploads"); }
    public Path outputs() { return dir("outputs"); }
    public Path topaz() { return dir("topaz"); }
    public Path generated() { return dir("generated"); }
    public Path refs() { return dir("refs"); }
    public Path videos() { return dir("videos"); }
    public Path videoInputs() { return dir("videoInputs"); }
    public Path materials() { return dir("materials"); }

    /** 分类的出厂目录，不受自定义影响。 */
    public Path defaultDir(String key) {
        String rel = SPECS.containsKey(key) ? SPECS.get(key)[2] : key;
        Path path = root.resolve(rel).normalize();
        ensureDirectory(path);
        return path;
    }

    /** 把绝对路径换算成 {@code /files/<分类>/<文件名>} 形式的访问地址。 */
    public String localUrl(Path file, String category) {
        String categoryPart = "refs".equals(category) ? "generated/refs" : category;
        return "/files/" + categoryPart + "/" + file.getFileName();
    }

    /** 判断一个绝对路径是否落在数据根目录之内，用于挡住任意的路径穿越。 */
    public boolean insideRoot(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.startsWith(root);
    }

    /** 目录快照，给 {@code POST /api/storage} 的响应带上全部路径状态。 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<String, String> custom = customDirs();
        for (Map.Entry<String, String[]> entry : SPECS.entrySet()) {
            String key = entry.getKey();
            String[] spec = entry.getValue();
            Path current = dir(key);
            Path fallback = defaultDir(key);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", key);
            item.put("label", spec[0]);
            item.put("desc", spec[1]);
            item.put("path", current.toString());
            item.put("defaultPath", fallback.toString());
            item.put("isDefault", custom.get(key) == null || custom.get(key).isEmpty());
            items.add(item);
        }
        return items;
    }

    /**
     * 改某个分类的目录。传空串等于恢复默认。
     *
     * @return 该项的快照，附带 {@code noMove} 表示这个分类不会搬动已有文件
     */
    public Map<String, Object> setDir(String key, String directory) {
        if (!SPECS.containsKey(key)) {
            throw new IllegalArgumentException("未知的存储分类：" + key);
        }
        String dir = directory == null ? "" : directory.trim();
        Path target;
        if (dir.isEmpty()) {
            target = defaultDir(key);
        } else {
            target = Paths.get(dir).toAbsolutePath().normalize();
            ensureDirectory(target);
        }
        Map<String, String> custom = customDirs();
        if (dir.isEmpty()) {
            custom.remove(key);
        } else {
            custom.put(key, target.toString());
        }
        saveCustomDirs(custom);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("key", key);
        result.put("label", SPECS.get(key)[0]);
        result.put("path", target.toString());
        result.put("defaultPath", defaultDir(key).toString());
        // Topaz 的结果文件由外部程序写，搬目录会把它的记录指丢，所以只改「以后放哪」
        result.put("noMove", "topaz".equals(key));
        return result;
    }

    /** 恢复默认目录，等价于 {@code setDir(key, "")}。 */
    public Map<String, Object> resetDir(String key) {
        return setDir(key, "");
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, String> customDirs() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(stateFile)) {
            return out;
        }
        try {
            String text = new String(Files.readAllBytes(stateFile), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> parsed = JsonSupport.parseObject(text);
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (SPECS.containsKey(entry.getKey()) && entry.getValue() != null) {
                    out.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (IOException ignored) {
            // 状态文件坏了就当没自定义过，用默认目录继续跑
        } catch (IllegalArgumentException ignored) {
            // 同上：内容不是合法 JSON 时退回默认
        }
        return out;
    }

    private void saveCustomDirs(Map<String, String> custom) {
        ensureDirectory(root);
        try {
            String text = JsonSupport.write(custom);
            Files.write(stateFile, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new IllegalStateException("无法保存存储路径配置：" + err.getMessage(), err);
        }
    }

    private static void ensureDirectory(Path path) {
        if (Files.isDirectory(path)) {
            return;
        }
        try {
            Files.createDirectories(path);
        } catch (IOException err) {
            throw new IllegalStateException("无法创建数据目录 " + path + "：" + err.getMessage(), err);
        }
    }

    /** 供文件服务写盘时确认目录存在。 */
    public Path ensure(Path path) {
        ensureDirectory(path);
        return path;
    }

    /** 读一个文件的字节流；不存在时抛 {@link IllegalArgumentException}。 */
    public InputStream open(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在：" + file.getFileName());
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException err) {
            throw new IllegalStateException("无法读取文件：" + err.getMessage(), err);
        }
    }
}

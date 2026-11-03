package com.lumenforge.service.storage;

import com.lumenforge.common.util.Ids;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.mapper.storage.StoredFileMapper;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地媒体文件的统一落盘与登记入口。
 *
 * <p>对应 Python 版 {@code app/shared_files.py} 与各处 {@code register_ref} 的公共部分：
 * 写盘、登记 {@code stored_files}、算 sha256 去重、以及在没人引用时删掉文件。
 *
 * <p>所有对外暴露的文件都必须经这里登记 —— 否则 {@code OutputFileController} 找不到
 * 分类目录，也就没法按 {@code /files/<category>/<name>} 提供服务。
 */
@Service
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    /** 单个文件的大小上限，与参考图限制保持一致。 */
    public static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;

    private final StoragePaths paths;
    private final StoredFileMapper files;

    public FileStorageService(StoragePaths paths, StoredFileMapper files) {
        this.paths = paths;
        this.files = files;
    }

    /**
     * 把一段字节写成受控目录里的文件并登记。
     *
     * @param category     分类键，决定落在哪个子目录
     * @param originalName 原始文件名，只用于展示和推断扩展名
     * @param mimeType     可选的 MIME，空则由扩展名推断
     */
    @Transactional
    public StoredFile store(String category, byte[] buffer, String originalName, String mimeType) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("文件内容是空的");
        }
        if (buffer.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("单个文件不能超过 " + (MAX_FILE_BYTES / (1024 * 1024)) + "MB");
        }
        String ext = extensionOf(originalName);
        String storageName = Ids.nextId(storagePrefix(category)) + ext;
        Path dir = paths.dir(category);
        Path dest = dir.resolve(storageName).normalize();
        if (!paths.insideRoot(dest)) {
            throw new IllegalStateException("目标路径越出了数据目录");
        }
        try {
            Files.write(dest, buffer);
        } catch (IOException err) {
            throw new IllegalStateException("无法写入文件：" + err.getMessage(), err);
        }
        String relative = relativize(dest);
        StoredFile record = new StoredFile(
                Ids.nextId("sf"),
                category,
                originalName == null || originalName.trim().isEmpty() ? storageName : originalName.trim(),
                storageName,
                relative,
                mimeType == null || mimeType.trim().isEmpty() ? guessMime(ext) : mimeType.trim(),
                buffer.length,
                sha256(buffer));
        return files.save(record);
    }

    /**
     * 直接引用一个已存在的文件，<b>不复制</b>。
     *
     * <p>素材库的图已经落在 {@code materials/} 下，再复制一份纯属浪费磁盘；这里只登记元数据。
     */
    @Transactional
    public StoredFile register(Path existing, String category, String originalName, String mimeType) {
        Path path = existing.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("素材文件已不存在，无法登记");
        }
        if (!paths.insideRoot(path)) {
            throw new IllegalArgumentException("文件不在受控数据目录内，已拒绝");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException err) {
            throw new IllegalStateException("无法读取文件大小：" + err.getMessage(), err);
        }
        String relative = relativize(path);
        String storageName = path.getFileName().toString();
        String ext = extensionOf(storageName);
        StoredFile record = new StoredFile(
                Ids.nextId("sf"),
                category,
                originalName == null || originalName.trim().isEmpty() ? storageName : originalName.trim(),
                storageName,
                relative,
                mimeType == null || mimeType.trim().isEmpty() ? guessMime(ext) : mimeType.trim(),
                size,
                null);
        return files.save(record);
    }

    /** 按 id 查登记记录。 */
    public StoredFile require(String fileId) {
        return files.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件记录不存在：" + fileId));
    }

    /** 登记记录对应的磁盘路径。 */
    public Path resolve(StoredFile record) {
        return paths.root().resolve(record.getRelativePath()).normalize();
    }

    /** 给前端用的文件视图。 */
    public Map<String, Object> view(StoredFile record) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Path path = resolve(record);
        out.put("id", record.getId());
        out.put("category", record.getCategory());
        out.put("originalName", record.getOriginalName());
        out.put("fileName", record.getStorageName());
        out.put("localPath", path.toString());
        out.put("localUrl", localUrlFor(record));
        out.put("mimeType", record.getMimeType());
        out.put("size", record.getSizeBytes());
        out.put("createdAt", record.getCreatedAt());
        out.put("exists", Files.isRegularFile(path));
        return out;
    }

    /**
     * 文件的访问地址。
     *
     * <p>按文件实际所在目录反查分类，这样用户把 {@code generated} 改到别的盘之后
     * 地址依然正确。
     */
    public String localUrlFor(StoredFile record) {
        String category = record.getCategory();
        if ("refs".equals(category)) {
            return "/files/generated/refs/" + record.getStorageName();
        }
        if ("videoInputs".equals(category)) {
            return "/files/video-inputs/" + record.getStorageName();
        }
        return "/files/" + category + "/" + record.getStorageName();
    }

    /** 删除登记记录，并在没人引用时删掉磁盘文件。 */
    @Transactional
    public void delete(String fileId) {
        StoredFile record = files.findById(fileId).orElse(null);
        if (record == null) {
            return;
        }
        files.delete(record);
        unlinkIfUnused(resolve(record));
    }

    /** 没有登记记录引用时就删掉磁盘文件；文件已不在也算成功。 */
    public void unlinkIfUnused(Path file) {
        if (file == null) {
            return;
        }
        Path path = file.toAbsolutePath().normalize();
        if (!paths.insideRoot(path)) {
            // 不是我们管的文件，绝不删
            return;
        }
        if (files.findByRelativePath(relativize(path)).isPresent()) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException err) {
            LOGGER.warn("删除文件失败 {}：{}", path, err.getMessage());
        }
    }

    /** 相对数据根目录的路径，统一用正斜杠，方便跨平台比较。 */
    public String relativize(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path rel = paths.root().relativize(normalized);
        return rel.toString().replace('\\', '/');
    }

    /** 按分类决定生成文件名的前缀，让磁盘上一眼能看出产物来源。 */
    public static String storagePrefix(String category) {
        if ("uploads".equals(category)) {
            return "up";
        }
        if ("outputs".equals(category)) {
            return "out";
        }
        if ("refs".equals(category)) {
            return "ref";
        }
        if ("videos".equals(category)) {
            return "vid";
        }
        if ("videoInputs".equals(category)) {
            return "vin";
        }
        if ("materials".equals(category)) {
            return "mat";
        }
        if ("topaz".equals(category)) {
            return "tpz";
        }
        return "gen";
    }

    /** 取小写扩展名（含点）；没有则返回空串。 */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.trim();
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot == clean.length() - 1) {
            return "";
        }
        String ext = clean.substring(dot).toLowerCase(Locale.ROOT);
        if (ext.length() < 2 || ext.length() > 6) {
            return "";
        }
        for (int i = 1; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) {
                return "";
            }
        }
        return ext;
    }

    /** 按扩展名猜 MIME，识别不了时给 {@code application/octet-stream}。 */
    public static String guessMime(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (".png".equals(e)) { return "image/png"; }
        if (".jpg".equals(e) || ".jpeg".equals(e)) { return "image/jpeg"; }
        if (".webp".equals(e)) { return "image/webp"; }
        if (".gif".equals(e)) { return "image/gif"; }
        if (".bmp".equals(e)) { return "image/bmp"; }
        if (".mp4".equals(e)) { return "video/mp4"; }
        if (".mov".equals(e)) { return "video/quicktime"; }
        if (".webm".equals(e)) { return "video/webm"; }
        if (".mp3".equals(e)) { return "audio/mpeg"; }
        if (".wav".equals(e)) { return "audio/wav"; }
        if (".m4a".equals(e)) { return "audio/mp4"; }
        if (".json".equals(e)) { return "application/json"; }
        if (".txt".equals(e)) { return "text/plain;charset=UTF-8"; }
        return "application/octet-stream";
    }

    /** 参考图允许的扩展名集合，键为扩展名、值为 MIME。 */
    public static final Map<String, String> IMAGE_MIME_BY_EXT = new LinkedHashMap<String, String>();

    static {
        IMAGE_MIME_BY_EXT.put(".png", "image/png");
        IMAGE_MIME_BY_EXT.put(".jpg", "image/jpeg");
        IMAGE_MIME_BY_EXT.put(".jpeg", "image/jpeg");
        IMAGE_MIME_BY_EXT.put(".webp", "image/webp");
        IMAGE_MIME_BY_EXT.put(".gif", "image/gif");
        IMAGE_MIME_BY_EXT.put(".bmp", "image/bmp");
    }

    /** 把用户传的字符串路径解析成绝对路径，并挡住穿越。 */
    public Path safeResolve(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path path = Paths.get(raw.trim()).toAbsolutePath().normalize();
        if (!paths.insideRoot(path)) {
            throw new IllegalArgumentException("文件不在受控数据目录内，已拒绝");
        }
        return path;
    }

    private static String sha256(byte[] buffer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(buffer);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException err) {
            // SHA-256 是 JDK 必备算法，走不到这里
            return null;
        }
    }
}

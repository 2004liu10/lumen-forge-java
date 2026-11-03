package com.lumenforge.entity.storage;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 本地媒体文件索引。
 *
 * <p>二进制本体留在受控磁盘目录里，这张表只记「这个文件是什么、在哪、多大」。
 * 参考图库、视频输入、素材、分镜场景都通过 {@code file_id} 引用这里的一行。
 */
@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    @Column(length = 255)
    private String id;

    /** uploads / outputs / generated / videos / materials 等分类。 */
    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    /** 磁盘上的随机化文件名，避免同名覆盖与路径穿越。 */
    @Column(name = "storage_name", nullable = false, length = 255)
    private String storageName;

    /** 相对于 storage root 的路径，反斜杠已归一成正斜杠。 */
    @Column(name = "relative_path", nullable = false, length = 1000)
    private String relativePath;

    @Column(name = "mime_type", length = 160)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** DDL 里是 CHAR(64)，Hibernate 默认按 VARCHAR 校验会失败，必须显式声明 columnDefinition。 */
    @Column(length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StoredFile() {
    }

    public StoredFile(String id, String category, String originalName, String storageName,
                      String relativePath, String mimeType, long sizeBytes, String sha256) {
        this.id = id;
        this.category = category;
        this.originalName = originalName;
        this.storageName = storageName;
        this.relativePath = relativePath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public String getOriginalName() { return originalName; }
    public String getStorageName() { return storageName; }
    public String getRelativePath() { return relativePath; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setMimeType(String value) { mimeType = value; }
}

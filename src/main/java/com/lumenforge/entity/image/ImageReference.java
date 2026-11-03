package com.lumenforge.entity.image;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 可复用的图生图参考素材。本机文件走 fileId（指向 stored_files），
 * 公网链接走 remoteUrl（不落盘、不复制），两者互斥。
 */
@Entity
@Table(name = "image_references")
public class ImageReference {
    @Id
    @Column(name = "id", length = 255, nullable = false)
    private String id;

    @Column(name = "file_id", length = 255)
    private String fileId;

    @Column(name = "remote_url", length = 2000)
    private String remoteUrl;

    @Column(name = "original_name", length = 500, nullable = false)
    private String originalName;

    /** upload / generated / link */
    @Column(name = "source", length = 32, nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ImageReference() {
    }

    public ImageReference(String fileId, String remoteUrl, String originalName, String source) {
        this.id = Ids.nextId("ir");
        this.fileId = fileId;
        this.remoteUrl = remoteUrl;
        this.originalName = originalName;
        this.source = source;
        this.createdAt = LocalDateTime.now();
    }

    /** 复用已失效的图库记录时，需要沿用原 id 重新登记，避免重复复用产生多份副本。 */
    public void adoptId(String id) {
        if (id != null && !id.trim().isEmpty()) this.id = id.trim();
    }

    public String getId() {
        return id;
    }

    public String getFileId() {
        return fileId;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getSource() {
        return source;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isLink() {
        return remoteUrl != null && !remoteUrl.trim().isEmpty();
    }
}

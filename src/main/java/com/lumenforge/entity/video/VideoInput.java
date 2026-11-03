package com.lumenforge.entity.video;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 视频输入素材（首帧 / 尾帧 / 送去视频的原图）。
 *
 * <p>二进制本体由 {@code stored_files} 登记，这里只记引用关系；原始文件名、大小和
 * MIME 都从 {@code file_id} 指向的记录里读，表里不重复存一份。
 */
@Entity
@Table(name = "video_inputs")
public class VideoInput {
    @Id private String id;
    @Column(name = "file_id", nullable = false, length = 255) private String fileId;
    @Column(nullable = false, length = 32) private String source;
    @Column(name = "source_task_id", length = 255) private String sourceTaskId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected VideoInput() {}

    public VideoInput(String fileId, String source, String sourceTaskId) {
        this.id = Ids.nextId("vin");
        this.fileId = fileId;
        this.source = source == null || source.trim().isEmpty() ? "upload" : source;
        this.sourceTaskId = sourceTaskId;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getFileId() { return fileId; }
    public String getSource() { return source; }
    public String getSourceTaskId() { return sourceTaskId; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setFileId(String value) { fileId = value; }
    public void setSource(String value) { source = value; }
    public void setSourceTaskId(String value) { sourceTaskId = value; }
}

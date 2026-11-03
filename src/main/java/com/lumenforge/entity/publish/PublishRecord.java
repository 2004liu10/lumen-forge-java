package com.lumenforge.entity.publish;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 一条发布记录：哪个视频文件、什么时候填过表、什么时候真的发了出去。
 *
 * <p>对应 Python 版 {@code app/publish_log.py} 的 {@code store.publish_log} 一行。
 * 表上没有的字段（{@code path / size / mtimeMs / topics / filledAt / fillCount /
 * deletedAt} 的毫秒原值）全部收进 {@code metadata_json}，这样「同路径但内容改过」
 * 的身份判定（path + size + mtime 三者全等）能原样保留。
 *
 * <p>字段对应关系：
 * <ul>
 *   <li>{@code title} ← Python 的 {@code name}</li>
 *   <li>{@code description} ← Python 的 {@code desc}</li>
 *   <li>{@code status} ← {@code filled / published}</li>
 *   <li>{@code published_at} ← {@code publishedAt}</li>
 * </ul>
 */
@Entity
@Table(name = "publish_records")
public class PublishRecord {

    @Id
    @Column(length = 255)
    private String id;

    /** 目标平台；本模块固定 douyin（话题库默认来源同源）。 */
    @Column(nullable = false, length = 40)
    private String platform;

    /** 发布标题，对应 Python 的 name；可空。 */
    @Column(length = 500)
    private String title;

    /** 发布文案，对应 Python 的 desc。 */
    @Column(columnDefinition = "text")
    private String description;

    /** 关联的文件登记；本模块按路径识别文件，这里留空。 */
    @Column(name = "file_id", length = 255)
    private String fileId;

    /** filled（填过表）/ published（确认发过）。 */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "remote_work_id", length = 255)
    private String remoteWorkId;

    @Column(name = "remote_url", length = 2000)
    private String remoteUrl;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** 扩展信息：path / size / mtimeMs / topics / filledAt / fillCount / createdAt / deletedAt。 */
    @Column(name = "metadata_json", nullable = false, columnDefinition = "json")
    private String metadataJson = "{}";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PublishRecord() {
    }

    public PublishRecord(String id, String platform, String title, String description,
                         String status, LocalDateTime publishedAt, String metadataJson,
                         LocalDateTime createdAt) {
        this.id = id;
        this.platform = platform;
        this.title = title;
        this.description = description;
        this.status = status;
        this.publishedAt = publishedAt;
        this.metadataJson = metadataJson == null ? "{}" : metadataJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getPlatform() { return platform; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getFileId() { return fileId; }
    public String getStatus() { return status; }
    public String getRemoteWorkId() { return remoteWorkId; }
    public String getRemoteUrl() { return remoteUrl; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public String getMetadataJson() { return metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setTitle(String value) { title = value; }
    public void setDescription(String value) { description = value; }
    public void setStatus(String value) { status = value; }
    public void setPublishedAt(LocalDateTime value) { publishedAt = value; }
    public void setMetadataJson(String value) { metadataJson = value == null ? "{}" : value; }
}

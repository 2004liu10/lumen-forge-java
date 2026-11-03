package com.lumenforge.entity.material;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 素材条目缓存实体。
 *
 * <p>对应 Python 版 {@code app/materials.py:parse_items()} 产出的条目：
 * {@code {id, url, poster, title, ext, hls}}。Python 只把它放在内存里做 3 分钟缓存，
 * 这里落进 {@code material_items} 表 —— 结构上仍然是一份缓存（每次刷新整批替换）。
 *
 * <p>字段映射：{@code id}（素材唯一键 material_key）→ {@code source_item_id}；
 * {@code poster / ext / hls} 收在 {@code metadata_json}。
 */
@Entity
@Table(name = "material_items")
public class MaterialItem {

    @Id
    private String id;

    @Column(name = "source_id", nullable = false, length = 255)
    private String sourceId;

    @Column(name = "source_item_id", nullable = false, length = 255)
    private String sourceItemId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "media_url", length = 2000)
    private String mediaUrl;

    @Column(name = "file_id", length = 255)
    private String fileId;

    @Column(name = "tags_json", nullable = false, columnDefinition = "json")
    private String tagsJson = "[]";

    @Column(name = "metadata_json", nullable = false, columnDefinition = "json")
    private String metadataJson = "{}";

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    protected MaterialItem() {
    }

    public MaterialItem(String id, String sourceId, String sourceItemId, String title, String description,
                        String mediaUrl, String fileId, String tagsJson, String metadataJson,
                        LocalDateTime fetchedAt) {
        this.id = id;
        this.sourceId = sourceId;
        this.sourceItemId = sourceItemId;
        this.title = title;
        this.description = description;
        this.mediaUrl = mediaUrl;
        this.fileId = fileId;
        this.tagsJson = tagsJson == null ? "[]" : tagsJson;
        this.metadataJson = metadataJson == null ? "{}" : metadataJson;
        this.fetchedAt = fetchedAt == null ? LocalDateTime.now() : fetchedAt;
    }

    public String getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getSourceItemId() { return sourceItemId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getMediaUrl() { return mediaUrl; }
    public String getFileId() { return fileId; }
    public String getTagsJson() { return tagsJson; }
    public String getMetadataJson() { return metadataJson; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }

    public void setTitle(String value) { title = value; }
    public void setDescription(String value) { description = value; }
    public void setMediaUrl(String value) { mediaUrl = value; }
    public void setFileId(String value) { fileId = value; }
    public void setTagsJson(String value) { tagsJson = value; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public void setFetchedAt(LocalDateTime value) { fetchedAt = value; }
}

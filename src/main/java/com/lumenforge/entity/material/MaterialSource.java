package com.lumenforge.entity.material;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 素材分类（= 一条独立抓取接口）实体。
 *
 * <p>对应 Python 版 {@code app/models.py:new_material_source()} 的扁平字典：
 * 表里只强类型化了 {@code name / source_type / enabled / created_at / updated_at}，
 * 其余业务字段（{@code kind / mode / lines / clip / count / headers / apiKey / apiKeyHeader /
 * apiKeyPrefix / itemsPath / urlField / extraParams}）全部收在 {@code config_json}，
 * 与 Python 的扁平结构一一对应，避免每加一个抓取参数就改表。
 *
 * <p>{@code source_type} 存的是 {@code kind}（{@code video} / {@code image}），
 * 抓取模式 {@code mode}（{@code api} / {@code m3u8} / {@code redirect} / {@code douyin}）在 JSON 里。
 */
@Entity
@Table(name = "material_sources")
public class MaterialSource {

    @Id
    private String id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "config_json", nullable = false, columnDefinition = "json")
    private String configJson = "{}";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MaterialSource() {
    }

    public MaterialSource(String id, String name, String sourceType, boolean enabled, String configJson) {
        this.id = id;
        this.name = name;
        this.sourceType = sourceType;
        this.enabled = enabled;
        this.configJson = configJson == null ? "{}" : configJson;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSourceType() { return sourceType; }
    public boolean isEnabled() { return enabled; }
    public String getConfigJson() { return configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String value) { name = value; }
    public void setSourceType(String value) { sourceType = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setConfigJson(String value) { configJson = value; }

    /** 任何一次保存都刷新 updated_at，与 Python 每次改分类都重写记录一致。 */
    public void touch() { updatedAt = LocalDateTime.now(); }
}

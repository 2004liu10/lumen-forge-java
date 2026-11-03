package com.lumenforge.entity.topaz;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Topaz 本地视频增强任务。
 *
 * <p>对应 Python 版 {@code topaz_jobs} 表：源文件与结果文件都引用 {@code stored_files}，
 * 增强参数快照收在 {@code config_json}，进度是百分比（0-100），失败原因单独一列。
 *
 * <p>本期 Python 版只保留了「引擎探测 / 档位配置 / 候选清单」，
 * {@code start_enhance} / {@code start_batch} / {@code cancel_enhance} / {@code restore}
 * 都是抛未实现的空壳，所以这张表目前只有「清空失败记录」会读删。
 */
@Entity
@Table(name = "topaz_jobs")
public class TopazJob {
    @Id private String id;
    @Column(name = "source_file_id", nullable = false, length = 255) private String sourceFileId;
    @Column(name = "output_file_id", length = 255) private String outputFileId;
    @Column(nullable = false, length = 32) private String status;
    @Column(length = 160) private String preset;
    /** DDL 是 DECIMAL(5,2)，必须显式声明精度，否则 Hibernate 会按别的类型校验失败。 */
    @Column(precision = 5, scale = 2) private BigDecimal progress;
    @Column(name = "config_json", nullable = false, columnDefinition = "json") private String configJson = "{}";
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;

    protected TopazJob() {}

    public TopazJob(String sourceFileId, String preset, String configJson) {
        this.id = com.lumenforge.common.util.Ids.nextId("tpz");
        this.sourceFileId = sourceFileId;
        this.preset = preset;
        this.configJson = configJson == null ? "{}" : configJson;
        this.status = "queued";
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getSourceFileId() { return sourceFileId; }
    public String getOutputFileId() { return outputFileId; }
    public String getStatus() { return status; }
    public String getPreset() { return preset; }
    public BigDecimal getProgress() { return progress; }
    public String getConfigJson() { return configJson; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void setSourceFileId(String value) { sourceFileId = value; }
    public void setOutputFileId(String value) { outputFileId = value; }
    public void setStatus(String value) { status = value; }
    public void setPreset(String value) { preset = value; }
    public void setProgress(BigDecimal value) { progress = value; }
    public void setConfigJson(String value) { configJson = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setFinishedAt(LocalDateTime value) { finishedAt = value; }
}

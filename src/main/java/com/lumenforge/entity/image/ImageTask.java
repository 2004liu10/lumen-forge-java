package com.lumenforge.entity.image;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/** 图片生成任务快照；任务保留提示词、模型名和结果 JSON，模型被删除后历史仍可查看。 */
@Entity
@Table(name = "image_tasks")
public class ImageTask {
    @Id private String id;
    @Column(name = "model_id", length = 255) private String modelId;
    @Column(name = "model_name", nullable = false, length = 160) private String modelName;
    @Column(nullable = false, columnDefinition = "text") private String prompt;
    @Column(length = 80) private String size;
    @Column(name = "image_count", nullable = false) private int imageCount;
    @Column(nullable = false, length = 32) private String status;
    @Lob @Column(name = "result_json", nullable = false, columnDefinition = "longtext") private String resultJson = "{}";
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(nullable = false) private int attempts;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;

    protected ImageTask() {}

    public ImageTask(String modelId, String modelName, String prompt, String size, int imageCount) {
        this.id = Ids.nextId("img");
        this.modelId = modelId;
        this.modelName = modelName;
        this.prompt = prompt;
        this.size = size;
        this.imageCount = imageCount;
        this.status = "queued";
        this.resultJson = "{\"images\":[]}";
        this.attempts = 0;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getModelId() { return modelId; }
    public String getModelName() { return modelName; }
    public String getPrompt() { return prompt; }
    public String getSize() { return size; }
    public int getImageCount() { return imageCount; }
    public String getStatus() { return status; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void setModelId(String value) { modelId = value; }
    public void setModelName(String value) { modelName = value; }
    public void setPrompt(String value) { prompt = value; }
    public void setSize(String value) { size = value; }
    public void setImageCount(int value) { imageCount = value; }
    public void setStatus(String value) { status = value; }
    public void setResultJson(String value) { resultJson = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setAttempts(int value) { attempts = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setFinishedAt(LocalDateTime value) { finishedAt = value; }
}

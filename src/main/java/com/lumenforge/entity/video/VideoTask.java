package com.lumenforge.entity.video;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 异步视频生成任务。
 *
 * <p>对应 Python 版 {@code app/models.py} 的 {@code new_video_task}：模型名与提示词在提交时
 * 快照下来，模型被删除后历史任务仍然可读。参数收在 {@code params_json}，轮询状态、远端地址、
 * 本地产物和抽取出的尾帧收在 {@code result_json}。
 */
@Entity
@Table(name = "video_tasks")
public class VideoTask {
    @Id private String id;
    @Column(name = "model_id", length = 255) private String modelId;
    @Column(name = "model_name", nullable = false, length = 160) private String modelName;
    @Column(nullable = false, columnDefinition = "text") private String prompt;
    @Column(name = "params_json", nullable = false, columnDefinition = "json") private String paramsJson = "{}";
    @Column(name = "input_id", length = 255) private String inputId;
    @Column(name = "end_input_id", length = 255) private String endInputId;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "remote_task_id", length = 255) private String remoteTaskId;
    @Lob @Column(name = "result_json", columnDefinition = "longtext") private String resultJson;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(nullable = false) private int attempts;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;

    protected VideoTask() {}

    public VideoTask(String modelId, String modelName, String prompt, String paramsJson, String inputId, String endInputId) {
        this.id = Ids.nextId("vid");
        this.modelId = modelId;
        this.modelName = modelName;
        this.prompt = prompt;
        this.paramsJson = paramsJson == null ? "{}" : paramsJson;
        this.inputId = inputId;
        this.endInputId = endInputId;
        this.status = "queued";
        this.remoteTaskId = null;
        this.resultJson = "{}";
        this.errorMessage = null;
        this.attempts = 0;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getModelId() { return modelId; }
    public String getModelName() { return modelName; }
    public String getPrompt() { return prompt; }
    public String getParamsJson() { return paramsJson; }
    public String getInputId() { return inputId; }
    public String getEndInputId() { return endInputId; }
    public String getStatus() { return status; }
    public String getRemoteTaskId() { return remoteTaskId; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void setModelId(String value) { modelId = value; }
    public void setModelName(String value) { modelName = value; }
    public void setPrompt(String value) { prompt = value; }
    public void setParamsJson(String value) { paramsJson = value; }
    public void setInputId(String value) { inputId = value; }
    public void setEndInputId(String value) { endInputId = value; }
    public void setStatus(String value) { status = value; }
    public void setRemoteTaskId(String value) { remoteTaskId = value; }
    public void setResultJson(String value) { resultJson = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setAttempts(int value) { attempts = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setFinishedAt(LocalDateTime value) { finishedAt = value; }
}

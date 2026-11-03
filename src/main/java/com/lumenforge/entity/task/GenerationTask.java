package com.lumenforge.entity.task;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.util.UUID;

/**
 * 一条生成任务的持久化快照。参数快照和远端任务号与当前画布解耦，
 * 画布后续修改或服务重启都不会改变已经排队任务的输入。
 */
@Entity
@Table(name = "generation_tasks")
public class GenerationTask {
    @Id private String id;
    @Column private String canvasId;
    @Column(nullable = false, length = 160) private String canvasName;
    @Column(nullable = false, length = 24) private String mode;
    @Column(nullable = false, length = 200) private String appId;
    @Column(nullable = false, length = 32) private String status;
    @Column(length = 1000) private String message;
    private int attempts;
    private int maxAttempts;
    private String accountId;
    private String accountName;
    private String pinnedAccountId;
    private String remoteTaskId;
    @Column(name = "error_message", length = 2000) private String error;
    @Lob @Column(nullable = false) private String canvasConfigJson = "{}";
    @Lob @Column(nullable = false) private String outputsJson = "[]";
    @Lob @Column(nullable = false) private String logJson = "[]";
    private Long createdAt;
    private Long startedAt;
    private Long finishedAt;
    private Long nextPollAt;
    private int pollErrors;
    private boolean cancelRequested;
    private boolean imported;

    protected GenerationTask() {}

    public GenerationTask(String canvasId, String canvasName, String mode, String appId,
                          String canvasConfigJson, String pinnedAccountId, int maxAttempts) {
        this.id = "tk_" + UUID.randomUUID();
        this.canvasId = canvasId;
        this.canvasName = canvasName;
        this.mode = mode;
        this.appId = appId;
        this.canvasConfigJson = canvasConfigJson;
        this.pinnedAccountId = pinnedAccountId;
        this.maxAttempts = maxAttempts;
        this.status = "queued";
        this.message = "排队中";
        this.createdAt = System.currentTimeMillis();
        this.outputsJson = "[]";
        this.logJson = "[]";
    }

    public String getId() { return id; }
    public String getCanvasId() { return canvasId; }
    public String getCanvasName() { return canvasName; }
    public String getMode() { return mode; }
    public String getAppId() { return appId; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getAccountId() { return accountId; }
    public String getAccountName() { return accountName; }
    public String getPinnedAccountId() { return pinnedAccountId; }
    public String getRemoteTaskId() { return remoteTaskId; }
    public String getError() { return error; }
    public String getCanvasConfigJson() { return canvasConfigJson; }
    public String getOutputsJson() { return outputsJson; }
    public String getLogJson() { return logJson; }
    public Long getCreatedAt() { return createdAt; }
    public Long getStartedAt() { return startedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public Long getNextPollAt() { return nextPollAt; }
    public int getPollErrors() { return pollErrors; }
    public boolean isCancelRequested() { return cancelRequested; }
    public boolean isImported() { return imported; }

    public void setStatus(String value) { status = value; }
    public void setMessage(String value) { message = value; }
    public void setAttempts(int value) { attempts = value; }
    public void setAccountId(String value) { accountId = value; }
    public void setAccountName(String value) { accountName = value; }
    public void setRemoteTaskId(String value) { remoteTaskId = value; }
    public void setError(String value) { error = value; }
    public void setOutputsJson(String value) { outputsJson = value; }
    public void setLogJson(String value) { logJson = value; }
    public void setStartedAt(Long value) { startedAt = value; }
    public void setFinishedAt(Long value) { finishedAt = value; }
    public void setNextPollAt(Long value) { nextPollAt = value; }
    public void setPollErrors(int value) { pollErrors = value; }
    public void setCancelRequested(boolean value) { cancelRequested = value; }
    public void setImported(boolean value) { imported = value; }
}





package com.lumenforge.entity.canvas;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Lob;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "canvases")
public class Canvas {
    @Id
    private String id;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, length = 24)
    private String mode;
    @Column(nullable = false, length = 500)
    private String baseUrl;
    @Column(length = 200)
    private String appId;
    @Column(length = 200)
    private String appName;
    private String accountId;
    private boolean autoRetry;
    private int maxAttempts;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Lob
    @Column(nullable = false)
    private String configJson = "{}";

    protected Canvas() {}

    public Canvas(String name, String mode, String baseUrl, String appId, String appName,
                  String accountId, boolean autoRetry, int maxAttempts) {
        this.id = "cv_" + UUID.randomUUID();
        this.name = name;
        this.mode = mode;
        this.baseUrl = baseUrl;
        this.appId = appId;
        this.appName = appName;
        this.accountId = accountId;
        this.autoRetry = autoRetry;
        this.maxAttempts = maxAttempts;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getMode() { return mode; }
    public String getBaseUrl() { return baseUrl; }
    public String getAppId() { return appId; }
    public String getAppName() { return appName; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String value) { accountId = value; }
    public boolean isAutoRetry() { return autoRetry; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { configJson = value; }

    public void update(String name, String mode, String baseUrl, String appId, String appName,
                       String accountId, boolean autoRetry, int maxAttempts) {
        this.name = name;
        this.mode = mode;
        this.baseUrl = baseUrl;
        this.appId = appId;
        this.appName = appName;
        this.accountId = accountId;
        this.autoRetry = autoRetry;
        this.maxAttempts = maxAttempts;
    }
}





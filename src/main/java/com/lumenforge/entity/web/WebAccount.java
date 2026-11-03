package com.lumenforge.entity.web;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 网页平台的一个账号（登录态 + 每日额度账本）。
 *
 * <p>对应 Python 版 {@code web_accounts}：额度按**自然日**滚动（0 点重置），
 * 登录态由桌面壳探测 cookie 后回写 {@code login_at} / {@code login_expires_at}。
 * 与 RunningHub 的 API Key 账号池完全独立：那边按并发派单，这边按每日额度轮换。
 */
@Entity
@Table(name = "web_accounts")
public class WebAccount {
    @Id private String id;
    @Column(name = "provider_slug", nullable = false, length = 80) private String providerSlug;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false) private boolean enabled;
    @Column(length = 1000) private String remark;
    /** 最近成功登录时间，Unix 毫秒；0 = 从没登过。 */
    @Column(name = "login_at") private Long loginAt;
    /** 网页登录态过期时间，Unix 毫秒；0 = 未探测到过期时刻。 */
    @Column(name = "login_expires_at") private Long loginExpiresAt;
    @Column(name = "quota_used", nullable = false) private int quotaUsed;
    @Column(name = "quota_day") private LocalDate quotaDay;
    @Column(name = "last_error", length = 500) private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    protected WebAccount() {}

    public WebAccount(String providerSlug, String name) {
        this.id = com.lumenforge.common.util.Ids.nextId("wa");
        this.providerSlug = providerSlug;
        this.name = name;
        this.enabled = true;
        this.loginAt = 0L;
        this.loginExpiresAt = 0L;
        this.quotaUsed = 0;
        this.lastError = "";
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getProviderSlug() { return providerSlug; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public String getRemark() { return remark; }
    public Long getLoginAt() { return loginAt; }
    public Long getLoginExpiresAt() { return loginExpiresAt; }
    public int getQuotaUsed() { return quotaUsed; }
    public LocalDate getQuotaDay() { return quotaDay; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(String value) { id = value; }
    public void setProviderSlug(String value) { providerSlug = value; }
    public void setName(String value) { name = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setRemark(String value) { remark = value; }
    public void setLoginAt(Long value) { loginAt = value; }
    public void setLoginExpiresAt(Long value) { loginExpiresAt = value; }
    public void setQuotaUsed(int value) { quotaUsed = value; }
    public void setQuotaDay(LocalDate value) { quotaDay = value; }
    public void setLastError(String value) { lastError = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}

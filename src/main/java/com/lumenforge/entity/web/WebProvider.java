package com.lumenforge.entity.web;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 一个可配置的网页端 AI 平台（豆包 / 千问 / 元宝 …）。
 *
 * <p>对应 Python 版 {@code web_providers}：库里存的是「这份配置」，字段白名单见
 * {@code app/web_providers.py} 的 {@code sanitize()}。DDL 只把 slug / name / enabled /
 * quota_per_day 提成列，页面地址、hosts、选择器候选数组和额度规则都收在 {@code config_json}，
 * 加一家平台只需要填一份配置，不用改代码。
 */
@Entity
@Table(name = "web_providers")
public class WebProvider {
    @Id private String id;
    @Column(nullable = false, length = 80) private String slug;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false) private boolean enabled;
    /** 单账号每日免费额度；0 = 不限制。与 config_json 里的 quota.daily 同值。 */
    @Column(name = "quota_per_day") private Integer quotaPerDay;
    @Column(name = "config_json", nullable = false, columnDefinition = "json") private String configJson = "{}";
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected WebProvider() {}

    public WebProvider(String slug, String name, boolean enabled, Integer quotaPerDay, String configJson) {
        this.id = com.lumenforge.common.util.Ids.nextId("wp");
        this.slug = slug;
        this.name = name;
        this.enabled = enabled;
        this.quotaPerDay = quotaPerDay;
        this.configJson = configJson == null ? "{}" : configJson;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public Integer getQuotaPerDay() { return quotaPerDay; }
    public String getConfigJson() { return configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(String value) { id = value; }
    public void setSlug(String value) { slug = value; }
    public void setName(String value) { name = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setQuotaPerDay(Integer value) { quotaPerDay = value; }
    public void setConfigJson(String value) { configJson = value; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}

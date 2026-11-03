package com.lumenforge.entity.account;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * API 账号持久化模型。密钥只以密文列保存；统计、额度与余额跟随账号记录，
 * 让调度器可以直接判断账号是否可用。
 */
@Entity
@Table(name = "provider_accounts")
public class Account {
    @Id private String id;
    @Column(nullable = false, length = 160) private String name;
    @Column(name = "api_key_ciphertext", nullable = false, length = 4096) private String apiKeyCiphertext;
    @Column(nullable = false, length = 8) private String site;
    private int concurrency;
    private boolean enabled;
    @Column(length = 1000) private String remark;
    private int dailyLimit;
    private int quotaUsed;
    private Long quotaWindowStart;
    private Long totalTasks;
    private Long successfulTasks;
    private Long failedTasks;
    @Column(name = "login_name_ciphertext", length = 4096) private String loginNameCiphertext;
    @Column(name = "login_pass_ciphertext", length = 4096) private String loginPassCiphertext;
    private Long loginAt;
    private Long loginExpiresAt;
    private Long claimAt;
    private Long claimGain;
    @Column(length = 500) private String claimNote;
    private Double remainCoins;
    private Double remainMoney;
    @Column(length = 20) private String currency;
    @Column(length = 80) private String apiType;
    private Integer currentTaskCounts;
    @Column(length = 1000) private String balanceError;
    private Long balanceUpdatedAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected Account() {}

    public Account(String name, String apiKeyCiphertext, String site, int concurrency,
                   String remark, int dailyLimit, String loginNameCiphertext, String loginPassCiphertext) {
        this.id = "acc_" + UUID.randomUUID();
        this.name = name;
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.site = site;
        this.concurrency = concurrency;
        this.enabled = true;
        this.remark = remark;
        this.dailyLimit = dailyLimit;
        this.quotaUsed = 0;
        this.totalTasks = 0L;
        this.successfulTasks = 0L;
        this.failedTasks = 0L;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public String getSite() { return site; }
    public int getConcurrency() { return concurrency; }
    public boolean isEnabled() { return enabled; }
    public String getRemark() { return remark; }
    public int getDailyLimit() { return dailyLimit; }
    public int getQuotaUsed() { return quotaUsed; }
    public Long getQuotaWindowStart() { return quotaWindowStart; }
    public Long getTotalTasks() { return totalTasks; }
    public Long getSuccessfulTasks() { return successfulTasks; }
    public Long getFailedTasks() { return failedTasks; }
    public String getLoginNameCiphertext() { return loginNameCiphertext; }
    public String getLoginPassCiphertext() { return loginPassCiphertext; }
    public Long getLoginAt() { return loginAt; }
    public Long getLoginExpiresAt() { return loginExpiresAt; }
    public Long getClaimAt() { return claimAt; }
    public Long getClaimGain() { return claimGain; }
    public String getClaimNote() { return claimNote; }
    public Double getRemainCoins() { return remainCoins; }
    public Double getRemainMoney() { return remainMoney; }
    public String getCurrency() { return currency; }
    public String getApiType() { return apiType; }
    public Integer getCurrentTaskCounts() { return currentTaskCounts; }
    public String getBalanceError() { return balanceError; }
    public Long getBalanceUpdatedAt() { return balanceUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String value) { name = value; }
    public void setApiKeyCiphertext(String value) { apiKeyCiphertext = value; }
    public void setSite(String value) { site = value; }
    public void setConcurrency(int value) { concurrency = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setRemark(String value) { remark = value; }
    public void setDailyLimit(int value) { dailyLimit = value; }
    public void setLoginNameCiphertext(String value) { loginNameCiphertext = value; }
    public void setLoginPassCiphertext(String value) { loginPassCiphertext = value; }
    public void setLoginAt(Long value) { loginAt = value; }
    public void setLoginExpiresAt(Long value) { loginExpiresAt = value; }
    public void setClaimAt(Long value) { claimAt = value; }
    public void setClaimGain(Long value) { claimGain = value; }
    public void setClaimNote(String value) { claimNote = value; }
    public void setQuotaUsed(int value) { quotaUsed = value; }
    public void setQuotaWindowStart(Long value) { quotaWindowStart = value; }

    public void recordTaskOutcome(boolean success) {
        totalTasks = (totalTasks == null ? 0L : totalTasks) + 1L;
        if (success) successfulTasks = (successfulTasks == null ? 0L : successfulTasks) + 1L;
        else failedTasks = (failedTasks == null ? 0L : failedTasks) + 1L;
    }

    public void consumeQuota() {
        if (dailyLimit <= 0) return;
        long now = System.currentTimeMillis();
        if (quotaWindowStart != null && now - quotaWindowStart >= 24L * 60L * 60L * 1000L) {
            quotaUsed = 0;
            quotaWindowStart = null;
        }
        if (quotaWindowStart == null) quotaWindowStart = now;
        quotaUsed++;
    }

    public void clearBalance() {
        remainCoins = null;
        remainMoney = null;
        currency = null;
        apiType = null;
        currentTaskCounts = null;
        balanceError = null;
        balanceUpdatedAt = null;
    }

    public void updateBalance(Double coins, Double money, String currency, String apiType,
                              Integer currentTasks, String error) {
        this.remainCoins = coins;
        this.remainMoney = money;
        this.currency = currency;
        this.apiType = apiType;
        this.currentTaskCounts = currentTasks;
        this.balanceError = error;
        this.balanceUpdatedAt = System.currentTimeMillis();
    }
}





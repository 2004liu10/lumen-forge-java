package com.lumenforge.entity.topic;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 话题库里的一条话题。
 *
 * <p>对应 Python 版 {@code app/models.py} 的 {@code new_topic} 与 {@code store.topic_library}：
 * 一条话题不绑定任何视频，只记「名字、从哪来的、用了几次、最后一次什么时候用」。
 *
 * <p>表上只有 {@code topic / category / used / used_at / created_at} 这几列是强类型的，
 * 其余（{@code sourceDesc / usedCount / lastUsedAt / createdAt} 的毫秒原值）都收在
 * {@code metadata_json} 里 —— 与 Python 的扁平字典一一对应，避免为了几个展示字段改表。
 */
@Entity
@Table(name = "topic_history")
public class TopicHistory {

    @Id
    @Column(length = 255)
    private String id;

    /** 归一化后的选题文本：去掉前导 #、去掉所有空白、最多 30 字。 */
    @Column(name = "topic", nullable = false, length = 1000)
    private String topic;

    /** 来源（douyin / manual / ai），对应 Python 的 {@code source}。 */
    @Column(length = 160)
    private String category;

    /** 是否被使用过；等价于 Python 的 {@code usedCount > 0}。 */
    @Column(nullable = false)
    private boolean used;

    /** 扩展信息：sourceDesc / usedCount / lastUsedAt(ms) / createdAt(ms)。 */
    @Column(name = "metadata_json", columnDefinition = "json")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    protected TopicHistory() {
    }

    public TopicHistory(String id, String topic, String category, boolean used,
                        String metadataJson, LocalDateTime createdAt, LocalDateTime usedAt) {
        this.id = id;
        this.topic = topic;
        this.category = category;
        this.used = used;
        this.metadataJson = metadataJson == null ? "{}" : metadataJson;
        this.createdAt = createdAt;
        this.usedAt = usedAt;
    }

    public String getId() { return id; }
    public String getTopic() { return topic; }
    public String getCategory() { return category; }
    public boolean isUsed() { return used; }
    public String getMetadataJson() { return metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUsedAt() { return usedAt; }

    public void setCategory(String value) { category = value; }
    public void setUsed(boolean value) { used = value; }
    public void setMetadataJson(String value) { metadataJson = value == null ? "{}" : value; }
    public void setUsedAt(LocalDateTime value) { usedAt = value; }
}

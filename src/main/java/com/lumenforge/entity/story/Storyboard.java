package com.lumenforge.entity.story;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 分镜剧本行。
 *
 * <p>Python 版把整份剧本（画风、角色、镜头）都放在一个 dict 里，Java 侧沿用
 * {@code storyboards} 表的六列结构：标题、状态单独成列，其余内容全部序列化进
 * {@code script_json}（角色里的 {@code refId} 还会被发布模块的引用计数扫到，键名不能改）。
 */
@Entity
@Table(name = "storyboards")
public class Storyboard {

    @Id
    @Column(length = 255)
    private String id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 32)
    private String status;

    @Lob
    @Column(name = "script_json", nullable = false, columnDefinition = "longtext")
    private String scriptJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Storyboard() {
    }

    public Storyboard(String title, String status, String scriptJson) {
        this.id = Ids.nextId("sb");
        this.title = title;
        this.status = status;
        this.scriptJson = scriptJson;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** 每次落库都刷新 updated_at：表上有 idx_storyboards_updated 索引，按它取最新剧本。 */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getScriptJson() { return scriptJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setTitle(String value) { title = value; }
    public void setStatus(String value) { status = value; }
    public void setScriptJson(String value) { scriptJson = value; }
}

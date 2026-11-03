package com.lumenforge.entity.video;

import com.lumenforge.common.util.Ids;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 视频模型配置实体。
 *
 * <p>对应 Python 版 {@code app/models.py} 的 {@code new_video_model}：默认时长、分辨率、
 * 比例与厂商扩展参数都收在 {@code config_json} 里，新增厂商协议不用改表。
 */
@Entity
@Table(name = "video_models")
public class VideoModel {
    @Id private String id;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 40) private String provider;
    @Column(name = "base_url", nullable = false, length = 1000) private String baseUrl;
    @Column(name = "api_key_ciphertext", length = 4096) private String apiKeyCiphertext;
    @Column(name = "model_name", nullable = false, length = 255) private String modelName;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "config_json", nullable = false, columnDefinition = "json") private String configJson = "{}";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected VideoModel() {}

    public VideoModel(String name, String provider, String baseUrl, String apiKeyCiphertext,
                      String modelName, boolean enabled, String configJson) {
        this.id = Ids.nextId("vm");
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.modelName = modelName;
        this.enabled = enabled;
        this.configJson = configJson == null ? "{}" : configJson;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public String getModelName() { return modelName; }
    public boolean isEnabled() { return enabled; }
    public String getConfigJson() { return configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setName(String value) { name = value; }
    public void setProvider(String value) { provider = value; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public void setApiKeyCiphertext(String value) { apiKeyCiphertext = value; }
    public void setModelName(String value) { modelName = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setConfigJson(String value) { configJson = value; }
}

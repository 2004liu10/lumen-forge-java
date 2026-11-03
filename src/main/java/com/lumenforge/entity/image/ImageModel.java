package com.lumenforge.entity.image;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/** 图片模型配置实体；厂商扩展选项收在 JSON 中，避免每种协议都改表。 */
@Entity
@Table(name = "image_models")
public class ImageModel {
    @Id private String id;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 40) private String protocol;
    @Column(name = "base_url", nullable = false, length = 1000) private String baseUrl;
    @Column(name = "api_key_ciphertext", length = 4096) private String apiKeyCiphertext;
    @Column(name = "model_name", nullable = false, length = 255) private String modelName;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "config_json", nullable = false, columnDefinition = "json") private String configJson = "{}";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected ImageModel() {}

    public ImageModel(String id, String name, String protocol, String baseUrl, String apiKeyCiphertext,
                      String modelName, boolean enabled, String configJson) {
        this.id = id;
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.modelName = modelName;
        this.enabled = enabled;
        this.configJson = configJson == null ? "{}" : configJson;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProtocol() { return protocol; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public String getModelName() { return modelName; }
    public boolean isEnabled() { return enabled; }
    public String getConfigJson() { return configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setName(String value) { name = value; }
    public void setProtocol(String value) { protocol = value; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public void setApiKeyCiphertext(String value) { apiKeyCiphertext = value; }
    public void setModelName(String value) { modelName = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setConfigJson(String value) { configJson = value; }
}

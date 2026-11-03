package com.lumenforge.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次图片生成的入参。
 *
 * <p>字段与 Python 版 {@code imagegen.generate_images(model=..., prompt=..., ...)} 对齐：
 * 模型配置（协议/地址/密钥/额外参数）与任务参数（提示词/尺寸/张数/参考图）打包在一起，
 * 由 {@link ImageGenerationClient} 按协议分流。</p>
 */
public class ImageRequest {
    /** 参考图：公网链接带 {@code url}，本机文件带 {@code dataUri}。两者至少有一个。 */
    public static class Ref {
        private String id;
        private String originalName;
        private String url;
        private String dataUri;
        private String mimeType;
        private long size;

        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getOriginalName() { return originalName; }
        public void setOriginalName(String value) { originalName = value; }
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
        public String getDataUri() { return dataUri; }
        public void setDataUri(String value) { dataUri = value; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String value) { mimeType = value; }
        public long getSize() { return size; }
        public void setSize(long value) { size = value; }
    }

    private String protocol;
    private String baseUrl;
    private String apiKey;
    private String akSecret;
    private String accountId;
    private String modelName;
    private String displayName;
    private String prompt = "";
    private String size = "";
    private int count = 1;
    private Boolean noWatermark;
    private Boolean supportsImageInput;
    private String extraParams;
    private final List<Ref> refs = new ArrayList<Ref>();

    public String getProtocol() { return protocol; }
    public void setProtocol(String value) { protocol = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getAkSecret() { return akSecret; }
    public void setAkSecret(String value) { akSecret = value; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String value) { accountId = value; }
    /** 厂商模型 ID。 */
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    /** 界面上显示的模型名称，用于拼错误文案。 */
    public String getDisplayName() { return displayName == null ? "" : displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getPrompt() { return prompt == null ? "" : prompt; }
    public void setPrompt(String value) { prompt = value; }
    public String getSize() { return size == null ? "" : size; }
    public void setSize(String value) { size = value; }
    public int getCount() { return count; }
    public void setCount(int value) { count = value; }
    /** true 表示不加水印；null 按「不加水印」处理（Python 里只有显式 False 才加水印）。 */
    public Boolean getNoWatermark() { return noWatermark; }
    public void setNoWatermark(Boolean value) { noWatermark = value; }
    public Boolean getSupportsImageInput() { return supportsImageInput; }
    public void setSupportsImageInput(Boolean value) { supportsImageInput = value; }
    /** 模型上的「额外参数」原样 JSON 字符串；可为空。 */
    public String getExtraParams() { return extraParams; }
    public void setExtraParams(String value) { extraParams = value; }

    public List<Ref> getRefs() { return refs; }
    public void addRef(Ref ref) { if (ref != null) refs.add(ref); }

    /** 只有 {@code noWatermark} 显式为 false 才需要加水印。 */
    public boolean watermarkEnabled() { return Boolean.FALSE.equals(noWatermark); }

    /** 参考图里带公网链接的那些。 */
    public List<Ref> urlRefs() {
        List<Ref> out = new ArrayList<Ref>();
        for (Ref ref : refs) if (ref.getUrl() != null && !ref.getUrl().trim().isEmpty()) out.add(ref);
        return out;
    }

    /** 参考图里走本机文件字节流的那些。 */
    public List<Ref> fileRefs() {
        List<Ref> out = new ArrayList<Ref>();
        for (Ref ref : refs) if (ref.getUrl() == null || ref.getUrl().trim().isEmpty()) out.add(ref);
        return out;
    }
}

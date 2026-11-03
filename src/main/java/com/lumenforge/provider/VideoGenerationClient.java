package com.lumenforge.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 视频生成客户端：按厂商分流到智谱 / 火山方舟 / 阿里云百炼三种异步视频接口。
 *
 * <<p>这是 Python 版 {@code app/providers/videogen.py} 的移植。四家的协议差异都收在这里：
 * 提交返回远端任务号，查询返回状态与视频地址，最后由 {@code download} 拉回本地。
 *
 * <p>本地豆包走的是客户端 CDP 桥（{@code doubao_bridge}），Java 版没有这个桥，
 * 因此豆包分支直接抛出可读的中文错误，不静默降级。
 *
 * <p>所有失败都抛 {@link IllegalArgumentException}，message 是可直接展示给用户的中文文案。
 */
@Component
public class VideoGenerationClient {
    private static final Logger LOG = LoggerFactory.getLogger(VideoGenerationClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(300);

    /** 智谱只接受这几个尺寸档，其它分辨率值会被忽略。 */
    private static final List<String> ZHIPU_SIZES = Arrays.asList(
            "720x480", "1024x1024", "1280x960", "960x1280",
            "1920x1080", "1080x1920", "2048x1080", "3840x2160");

    private static final Pattern ARK_LAST_FRAME = Pattern.compile(
            "seedance-1-0-pro|seedance-1-5-pro|seedance-2-0-pro|seedance-2-5");
    private static final Pattern DASHSCOPE_WAN27 = Pattern.compile("wan2\\.7");
    private static final Pattern DASHSCOPE_KF2V = Pattern.compile("kf2v");

    /** 输入图扩展名 → MIME，与 Python 的 INPUT_MIME_TYPES 一致。 */
    public static final Map<String, String> INPUT_MIME_BY_EXT = new LinkedHashMap<String, String>();

    static {
        INPUT_MIME_BY_EXT.put(".png", "image/png");
        INPUT_MIME_BY_EXT.put(".jpg", "image/jpeg");
        INPUT_MIME_BY_EXT.put(".jpeg", "image/jpeg");
        INPUT_MIME_BY_EXT.put(".webp", "image/webp");
        INPUT_MIME_BY_EXT.put(".bmp", "image/bmp");
        INPUT_MIME_BY_EXT.put(".gif", "image/gif");
    }

    private final RestTemplate http;
    private final RestTemplate downloader;
    private final ObjectMapper json;

    public VideoGenerationClient(RestTemplateBuilder builder, ObjectMapper json) {
        this.json = json;
        this.http = builder.setConnectTimeout(Duration.ofSeconds(20)).setReadTimeout(REQUEST_TIMEOUT).build();
        this.downloader = builder.setConnectTimeout(Duration.ofSeconds(20)).setReadTimeout(DOWNLOAD_TIMEOUT).build();
    }

    // ================================================================ 模型规格

    /** 提交 / 查询所需的模型快照；apiKey 已由上层解密。 */
    public static class ModelSpec {
        private final String name;
        private final String provider;
        private final String baseUrl;
        private final String apiKey;
        private final String modelId;
        private final String extraParams;

        public ModelSpec(String name, String provider, String baseUrl, String apiKey, String modelId, String extraParams) {
            this.name = name == null ? "" : name;
            this.provider = provider == null ? "" : provider;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.modelId = modelId == null ? "" : modelId;
            this.extraParams = extraParams == null ? "" : extraParams;
        }

        public String getName() { return name; }
        public String getProvider() { return provider; }
        public String getBaseUrl() { return baseUrl; }
        public String getApiKey() { return apiKey; }
        public String getModelId() { return modelId; }
        public String getExtraParams() { return extraParams; }
    }

    /** 首帧 / 尾帧快照；localPath 是磁盘绝对路径。 */
    public static class InputSpec {
        private final String id;
        private final String fileName;
        private final String originalName;
        private final String localPath;
        private final String localUrl;
        private final String mimeType;
        private final long size;

        public InputSpec(String id, String fileName, String originalName, String localPath,
                         String localUrl, String mimeType, long size) {
            this.id = id == null ? "" : id;
            this.fileName = fileName == null ? "" : fileName;
            this.originalName = originalName == null ? "" : originalName;
            this.localPath = localPath == null ? "" : localPath;
            this.localUrl = localUrl == null ? "" : localUrl;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.size = size;
        }

        public String getId() { return id; }
        public String getFileName() { return fileName; }
        public String getOriginalName() { return originalName; }
        public String getLocalPath() { return localPath; }
        public String getLocalUrl() { return localUrl; }
        public String getMimeType() { return mimeType; }
        public long getSize() { return size; }
    }

    /** 查询结果；status 取 running / success / failed。 */
    public static class VideoResult {
        private final String status;
        private final String videoUrl;
        private final String error;
        private final String raw;

        public VideoResult(String status, String videoUrl, String error, String raw) {
            this.status = status;
            this.videoUrl = videoUrl == null ? "" : videoUrl;
            this.error = error == null ? "" : error;
            this.raw = raw == null ? "" : raw;
        }

        public String getStatus() { return status; }
        public String getVideoUrl() { return videoUrl; }
        public String getError() { return error; }
        public String getRaw() { return raw; }
    }

    /** 下载结果。 */
    public static class VideoDownload {
        private final byte[] buffer;
        private final String ext;

        public VideoDownload(byte[] buffer, String ext) {
            this.buffer = buffer;
            this.ext = ext;
        }

        public byte[] getBuffer() { return buffer; }
        public String getExt() { return ext; }
    }

    // ================================================================ 厂商预设

    /** 四家服务商的出厂参数；前端「视频模型」表单直接用这份数据渲染下拉框。 */
    public static Map<String, Object> presets() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("zhipu", presetZhipu());
        out.put("ark", presetArk());
        out.put("dashscope", presetDashscope());
        out.put("doubao", presetDoubao());
        return out;
    }

    /** 某家服务商的预设；不认识的厂商返回 null。 */
    public static Map<String, Object> preset(String provider) {
        if ("zhipu".equals(provider)) return presetZhipu();
        if ("ark".equals(provider)) return presetArk();
        if ("dashscope".equals(provider)) return presetDashscope();
        if ("doubao".equals(provider)) return presetDoubao();
        return null;
    }

    /** 预设里的 baseUrl；不认识的厂商返回空串。 */
    public static String presetBaseUrl(String provider) {
        Map<String, Object> item = preset(provider);
        return item == null ? "" : String.valueOf(item.get("baseUrl"));
    }

    /** 这家服务商是否接受第二张图（尾帧）。 */
    public static boolean takesSecondImage(String provider, String modelId) {
        return "doubao".equals(provider) || lastFrameSupported(provider, modelId);
    }

    /** 是否支持尾帧：方舟只认 Pro 系列，百炼只认 kf2v / wan2.7。 */
    public static boolean lastFrameSupported(String provider, String modelId) {
        String model = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if ("ark".equals(provider)) return ARK_LAST_FRAME.matcher(model).find();
        if ("dashscope".equals(provider)) {
            return DASHSCOPE_KF2V.matcher(model).find() || DASHSCOPE_WAN27.matcher(model).find();
        }
        return false;
    }

    /** 这家接口允许的输入图上限（字节）。 */
    public static long maxImageBytes(String provider) {
        Map<String, Object> item = preset(provider);
        if (item == null) return 20L * 1024L * 1024L;
        return ((Number) item.get("maxImageBytes")).longValue();
    }

    private static Map<String, Object> presetZhipu() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("label", "智谱 BigModel");
        out.put("baseUrl", "https://open.bigmodel.cn");
        out.put("keyUrl", "https://bigmodel.cn/usercenter/proj-mgmt/apikeys");
        out.put("keyLabel", "去智谱开放平台创建 API Key");
        out.put("models", Arrays.asList("cogvideox-flash", "cogvideox-3"));
        out.put("freeModel", "cogvideox-flash");
        out.put("durations", Arrays.asList("5", "10"));
        out.put("resolutions", Arrays.asList(
                "1024x1024", "1280x960", "960x1280", "1920x1080", "1080x1920",
                "720x480", "2048x1080", "3840x2160"));
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("1024x1024", "1:1");
        labels.put("1280x960", "4:3");
        labels.put("960x1280", "3:4");
        labels.put("1920x1080", "16:9");
        labels.put("1080x1920", "9:16");
        labels.put("720x480", "3:2");
        labels.put("2048x1080", "≈17:9");
        labels.put("3840x2160", "16:9（4K）");
        out.put("sizeLabels", labels);
        out.put("ratios", new ArrayList<String>());
        out.put("maxImageBytes", 5L * 1024L * 1024L);
        out.put("note", "cogvideox-flash 目前免费，适合先跑通链路；单图 ≤5MB，时长只有 5 秒 / 10 秒");
        return out;
    }

    private static Map<String, Object> presetArk() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("label", "火山方舟 Seedance");
        out.put("baseUrl", "https://ark.cn-beijing.volces.com/api/v3");
        out.put("keyUrl", "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey");
        out.put("keyLabel", "去火山方舟控制台创建 API Key");
        out.put("models", Arrays.asList(
                "doubao-seedance-1-0-lite-i2v-250428",
                "doubao-seedance-1-0-pro-250528",
                "doubao-seedance-2-0-mini-260615",
                "doubao-seedance-2-5-260628"));
        out.put("freeModel", "");
        out.put("durations", Arrays.asList("5", "10"));
        out.put("resolutions", Arrays.asList("480p", "720p", "1080p"));
        out.put("ratios", Arrays.asList("1:1", "4:3", "3:4", "16:9", "9:16", "21:9"));
        out.put("sizeLabels", new LinkedHashMap<String, Object>());
        out.put("maxImageBytes", 30L * 1024L * 1024L);
        out.put("note", "要先在方舟控制台开通对应模型（Seedance 2.x 要求余额 >200 元或已购资源包）；查询结果只保留 7 天");
        return out;
    }

    private static Map<String, Object> presetDashscope() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("label", "阿里云百炼（通义万相）");
        out.put("baseUrl", "https://dashscope.aliyuncs.com");
        out.put("keyUrl", "https://bailian.console.aliyun.com/?apiKey=1#/api-key");
        out.put("keyLabel", "去阿里云百炼控制台创建 API Key");
        out.put("models", Arrays.asList(
                "wan2.2-kf2v-flash", "wan2.2-i2v-flash", "wan2.1-i2v-turbo",
                "wan2.1-i2v-plus", "wanx2.1-kf2v-plus"));
        out.put("freeModel", "wan2.2-i2v-flash");
        out.put("durations", Arrays.asList("5"));
        out.put("resolutions", Arrays.asList("480P", "720P", "1080P"));
        out.put("ratios", new ArrayList<String>());
        out.put("sizeLabels", new LinkedHashMap<String, Object>());
        out.put("ratioNote", "比例跟随输入图（该接口只提供 480P/720P/1080P 分辨率档）");
        out.put("maxImageBytes", 20L * 1024L * 1024L);
        out.put("note", "");
        return out;
    }

    private static Map<String, Object> presetDoubao() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("label", "本地豆包（客户端桥）");
        out.put("baseUrl", "http://127.0.0.1:9555");
        out.put("keyUrl", "");
        out.put("keyLabel", "");
        out.put("models", Arrays.asList("doubao-local-video"));
        out.put("freeModel", "");
        out.put("durations", Arrays.asList("5", "10", "15", "20", "25", "30"));
        out.put("resolutions", Arrays.asList("720P", "1080P"));
        out.put("ratios", Arrays.asList("自动", "16:9", "9:16", "4:3", "3:4", "1:1", "21:9"));
        out.put("sizeLabels", new LinkedHashMap<String, Object>());
        List<Map<String, Object>> tiers = new ArrayList<Map<String, Object>>();
        tiers.add(tier("2.5", "2.5", "旗舰画质 · 5 倍消耗 · 最长 30 秒"));
        tiers.add(tier("2.0", "2.0", "进阶画质 · 2 倍消耗 · 最长 30 秒"));
        tiers.add(tier("fast", "Fast", "Seedance 2.0 Fast · 快速出片"));
        tiers.add(tier("mini", "Mini", "Seedance 2.0 Mini · 日常省额度 · 最长 15 秒"));
        out.put("tiers", tiers);
        out.put("maxImageBytes", 20L * 1024L * 1024L);
        out.put("note", "Java 版没有接入本地豆包桥，这条协议暂时不可用");
        return out;
    }

    private static Map<String, Object> tier(String id, String label, String hint) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("label", label);
        out.put("hint", hint);
        return out;
    }

    // ================================================================ 提交 / 查询 / 下载

    /**
     * 提交一次视频生成请求。
     *
     * @return {@code {remoteTaskId}}，可附带 {@code applied} / {@code note}
     */
    public Map<String, Object> submit(ModelSpec model, String prompt, Map<String, Object> params,
                                      InputSpec image, InputSpec endImage) {
        checkModel(model);
        if (!filled(prompt)) throw new IllegalArgumentException("请填写提示词");
        if ("doubao".equals(model.getProvider())) throw doubaoNotConnected();

        Map<String, Object> safeParams = params == null ? new LinkedHashMap<String, Object>() : params;
        String imageUrl = image == null ? "" : readInputDataUri(image, maxImageBytes(model.getProvider()));
        String endImageUrl = endImage != null && lastFrameSupported(model.getProvider(), model.getModelId())
                ? readInputDataUri(endImage, maxImageBytes(model.getProvider()))
                : "";

        Map<String, Object> result;
        if ("zhipu".equals(model.getProvider())) {
            result = submitZhipu(model, prompt, safeParams, imageUrl);
        } else if ("ark".equals(model.getProvider())) {
            result = submitArk(model, prompt, safeParams, imageUrl, endImageUrl);
        } else if ("dashscope".equals(model.getProvider())) {
            result = submitDashscope(model, prompt, safeParams, imageUrl, endImageUrl);
        } else {
            throw new IllegalArgumentException("不认识的视频服务商：" + (model.getProvider().isEmpty() ? "（空）" : model.getProvider()));
        }
        return result;
    }

    /** 查询一次远端任务状态。 */
    public VideoResult query(ModelSpec model, String remoteTaskId) {
        checkModel(model);
        if (!filled(remoteTaskId)) throw new IllegalArgumentException("这条任务还没有远端任务 ID");
        if ("doubao".equals(model.getProvider())) throw doubaoNotConnected();
        if ("zhipu".equals(model.getProvider())) return queryZhipu(model, remoteTaskId);
        if ("ark".equals(model.getProvider())) return queryArk(model, remoteTaskId);
        if ("dashscope".equals(model.getProvider())) return queryDashscope(model, remoteTaskId);
        throw new IllegalArgumentException("不认识的视频服务商：" + (model.getProvider().isEmpty() ? "（空）" : model.getProvider()));
    }

    /** 把生成好的视频拉回本地。 */
    public VideoDownload download(String url) {
        if (!filled(url)) throw new IllegalArgumentException("接口没有返回视频地址");
        if (url.startsWith("doubao-bridge://")) throw doubaoNotConnected();
        byte[] buffer;
        try {
            buffer = downloader.getForObject(url, byte[].class);
        } catch (RestClientResponseException err) {
            throw new IllegalArgumentException("下载视频失败：HTTP " + err.getRawStatusCode());
        } catch (ResourceAccessException err) {
            if (err.getCause() instanceof SocketTimeoutException) throw new IllegalArgumentException("下载视频超时");
            throw new IllegalArgumentException("下载视频失败：" + message(err));
        }
        if (buffer == null || buffer.length == 0) throw new IllegalArgumentException("下载到的内容为空");
        return new VideoDownload(buffer, ".mp4");
    }

    /** 提交前置校验，文案与 Python 版逐字一致。 */
    public void checkModel(ModelSpec model) {
        if (preset(model.getProvider()) == null) {
            throw new IllegalArgumentException("不认识的视频服务商："
                    + (model.getProvider().isEmpty() ? "（空）" : model.getProvider()));
        }
        if ("doubao".equals(model.getProvider())) return;
        if (!filled(model.getModelId())) throw new IllegalArgumentException("这个视频模型还没有填写模型 ID");
        if (!filled(model.getApiKey())) throw new IllegalArgumentException("视频模型「" + model.getName() + "」还没有填写 API Key");
    }

    // ================================================================ 智谱

    private Map<String, Object> submitZhipu(ModelSpec model, String prompt, Map<String, Object> params, String imageUrl) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model.getModelId());
        body.put("prompt", prompt);
        body.put("watermark_enabled", false);
        if (filled(imageUrl)) body.put("image_url", imageUrl);
        if (filled(params.get("duration"))) body.put("duration", toInt(params.get("duration")));
        String resolution = text(params.get("resolution"));
        if (filled(resolution) && ZHIPU_SIZES.contains(resolution)) body.put("size", resolution);
        if (filled(params.get("seed"))) body.put("seed", toInt(params.get("seed")));
        applyExtraParams(body, model.getExtraParams());

        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), "/api/paas/v4/videos/generations"),
                model.getApiKey(), HttpMethod.POST, body, null);
        String taskId = firstNonEmpty(text(parsed.get("id")), text(parsed.get("request_id")));
        if (!filled(taskId)) throw new IllegalArgumentException("提交成功但没拿到任务 ID：" + truncate(toJson(parsed), 300));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("remoteTaskId", taskId);
        return out;
    }

    private VideoResult queryZhipu(ModelSpec model, String remoteTaskId) {
        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), "/api/paas/v4/async-result/" + urlEncode(remoteTaskId)),
                model.getApiKey(), HttpMethod.GET, null, null);
        String raw = text(parsed.get("task_status")).toUpperCase(Locale.ROOT);
        String status;
        if ("SUCCESS".equals(raw)) status = "success";
        else if ("FAIL".equals(raw)) status = "failed";
        else status = "running";

        String videoUrl = "";
        Object rows = parsed.get("video_result");
        if (rows instanceof List && !((List<?>) rows).isEmpty()) {
            Object first = ((List<?>) rows).get(0);
            if (first instanceof Map) videoUrl = text(((Map<?, ?>) first).get("url"));
        }
        String error = "";
        if ("failed".equals(status)) {
            error = firstNonEmpty(nestedMessage(parsed, "error"), text(parsed.get("message")));
            if (!filled(error)) error = "智谱返回任务失败";
        }
        return new VideoResult(status, videoUrl, error, raw);
    }

    // ================================================================ 火山方舟

    private Map<String, Object> submitArk(ModelSpec model, String prompt, Map<String, Object> params,
                                          String imageUrl, String endImageUrl) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(contentItem("text", prompt, null, null));
        if (filled(imageUrl)) content.add(contentItem("image_url", null, imageUrl, "first_frame"));
        if (filled(endImageUrl)) content.add(contentItem("image_url", null, endImageUrl, "last_frame"));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model.getModelId());
        body.put("content", content);
        body.put("watermark", false);
        String resolution = text(params.get("resolution"));
        if (filled(resolution)) body.put("resolution", resolution);
        String ratio = text(params.get("ratio"));
        if (filled(ratio)) body.put("ratio", ratio);
        if (filled(params.get("duration"))) body.put("duration", toInt(params.get("duration")));
        if (filled(params.get("seed"))) body.put("seed", toInt(params.get("seed")));
        applyExtraParams(body, model.getExtraParams());

        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), "/contents/generations/tasks"),
                model.getApiKey(), HttpMethod.POST, body, null);
        String taskId = text(parsed.get("id"));
        if (!filled(taskId)) throw new IllegalArgumentException("提交成功但没拿到任务 ID：" + truncate(toJson(parsed), 300));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("remoteTaskId", taskId);
        return out;
    }

    private VideoResult queryArk(ModelSpec model, String remoteTaskId) {
        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), "/contents/generations/tasks/" + urlEncode(remoteTaskId)),
                model.getApiKey(), HttpMethod.GET, null, null);
        String raw = text(parsed.get("status")).toLowerCase(Locale.ROOT);
        String status;
        if ("succeeded".equals(raw)) status = "success";
        else if ("failed".equals(raw) || "expired".equals(raw) || "cancelled".equals(raw) || "canceled".equals(raw)) status = "failed";
        else status = "running";

        String videoUrl = "";
        Object content = parsed.get("content");
        if (content instanceof Map) videoUrl = text(((Map<?, ?>) content).get("video_url"));
        String error = "";
        if ("failed".equals(status)) {
            error = firstNonEmpty(nestedMessage(parsed, "error"), "方舟任务状态：" + raw);
        }
        return new VideoResult(status, videoUrl, error, raw);
    }

    private Map<String, Object> contentItem(String type, String text, String url, String role) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", type);
        if ("text".equals(type)) {
            item.put("text", text == null ? "" : text);
        } else {
            Map<String, Object> image = new LinkedHashMap<String, Object>();
            image.put("url", url == null ? "" : url);
            item.put("image_url", image);
        }
        if (role != null) item.put("role", role);
        return item;
    }

    // ================================================================ 阿里云百炼

    private Map<String, Object> submitDashscope(ModelSpec model, String prompt, Map<String, Object> params,
                                                String imageUrl, String endImageUrl) {
        String modelId = model.getModelId().toLowerCase(Locale.ROOT);
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("prompt", prompt);
        String suffix;
        if (DASHSCOPE_WAN27.matcher(modelId).find()) {
            List<Map<String, Object>> media = new ArrayList<Map<String, Object>>();
            if (filled(imageUrl)) media.add(mediaItem("first_frame", imageUrl));
            if (filled(endImageUrl)) media.add(mediaItem("last_frame", endImageUrl));
            input.put("media", media);
            suffix = "/api/v1/services/aigc/video-generation/video-synthesis";
        } else if (DASHSCOPE_KF2V.matcher(modelId).find()) {
            if (filled(imageUrl)) input.put("first_frame_url", imageUrl);
            if (filled(endImageUrl)) input.put("last_frame_url", endImageUrl);
            suffix = "/api/v1/services/aigc/image2video/video-synthesis";
        } else {
            if (filled(imageUrl)) input.put("img_url", imageUrl);
            suffix = "/api/v1/services/aigc/video-generation/video-synthesis";
        }

        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("watermark", false);
        String resolution = text(params.get("resolution"));
        if (filled(resolution)) parameters.put("resolution", resolution);
        if (filled(params.get("duration"))) parameters.put("duration", toInt(params.get("duration")));
        if (filled(params.get("seed"))) parameters.put("seed", toInt(params.get("seed")));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model.getModelId());
        body.put("input", input);
        body.put("parameters", parameters);
        applyExtraParams(body, model.getExtraParams());

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-DashScope-Async", "enable");
        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), suffix),
                model.getApiKey(), HttpMethod.POST, body, headers);
        Object output = parsed.get("output");
        String taskId = output instanceof Map ? text(((Map<?, ?>) output).get("task_id")) : "";
        if (!filled(taskId)) throw new IllegalArgumentException("提交成功但没拿到任务 ID：" + truncate(toJson(parsed), 300));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("remoteTaskId", taskId);
        return out;
    }

    private VideoResult queryDashscope(ModelSpec model, String remoteTaskId) {
        Map<String, Object> parsed = videoRequest(joinUrl(model.getBaseUrl(), "/api/v1/tasks/" + urlEncode(remoteTaskId)),
                model.getApiKey(), HttpMethod.GET, null, null);
        Map<?, ?> output = parsed.get("output") instanceof Map ? (Map<?, ?>) parsed.get("output") : new LinkedHashMap<Object, Object>();
        String raw = text(output.get("task_status")).toUpperCase(Locale.ROOT);
        String status;
        if ("SUCCEEDED".equals(raw)) status = "success";
        else if ("FAILED".equals(raw) || "CANCELED".equals(raw) || "UNKNOWN".equals(raw)) status = "failed";
        else status = "running";

        String videoUrl = text(output.get("video_url"));
        String error = "";
        if ("failed".equals(status)) {
            error = firstNonEmpty(text(output.get("message")), "百炼任务状态：" + raw);
        }
        return new VideoResult(status, videoUrl, error, raw);
    }

    private Map<String, Object> mediaItem(String type, String url) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", type);
        item.put("url", url == null ? "" : url);
        return item;
    }

    // ================================================================ 输入图

    /** 把本地输入图读成 base64 data URI，并校验这家接口的大小上限。 */
    private String readInputDataUri(InputSpec input, long maxBytes) {
        if (input == null || !filled(input.getLocalPath())) throw new IllegalArgumentException("这条任务没有输入图片");
        Path path = Paths.get(input.getLocalPath());
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("输入图片的本地文件已被删除，请重新选择");
        byte[] buffer;
        try {
            buffer = Files.readAllBytes(path);
        } catch (IOException err) {
            throw new IllegalStateException("无法读取输入图片：" + err.getMessage(), err);
        }
        if (buffer.length > maxBytes) {
            throw new IllegalArgumentException("输入图片 " + fmtMb(buffer.length) + " 超过这家接口的上限 "
                    + fmtMb(maxBytes) + "，请换一张更小的图");
        }
        String mime = filled(input.getMimeType()) ? input.getMimeType() : "";
        if (!filled(mime)) {
            String ext = extensionOf(input.getFileName());
            mime = INPUT_MIME_BY_EXT.containsKey(ext) ? INPUT_MIME_BY_EXT.get(ext) : "image/png";
        }
        return "data:" + mime.toLowerCase(Locale.ROOT) + ";base64,"
                + Base64.getEncoder().encodeToString(buffer);
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String fmtMb(long bytes) {
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    // ================================================================ HTTP

    /**
     * 统一的视频接口调用：拼 Bearer 鉴权、解析 JSON、把 HTTP 错误翻成中文文案。
     */
    private Map<String, Object> videoRequest(String url, String apiKey, HttpMethod method,
                                             Map<String, Object> body, Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + apiKey);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) headers.set(entry.getKey(), entry.getValue());
        }
        HttpEntity<Object> entity = new HttpEntity<Object>(body, headers);
        String text;
        try {
            ResponseEntity<String> response = http.exchange(url, method, entity, String.class);
            text = response.getBody() == null ? "" : response.getBody();
        } catch (RestClientResponseException err) {
            throw new IllegalArgumentException("接口返回 " + err.getRawStatusCode() + "：" + errorDetail(err.getResponseBodyAsString()));
        } catch (ResourceAccessException err) {
            if (err.getCause() instanceof SocketTimeoutException) {
                throw new IllegalArgumentException("请求超时：视频接口长时间没有响应");
            }
            throw new IllegalArgumentException("无法连接视频接口：" + message(err));
        }
        Map<String, Object> parsed;
        try {
            parsed = json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception err) {
            throw new IllegalArgumentException("接口返回的不是 JSON：" + truncate(text, 300));
        }
        if (parsed == null) throw new IllegalArgumentException("接口返回的不是 JSON：" + truncate(text, 300));
        return parsed;
    }

    /** 从错误响应里挑出最有信息量的一条文案。 */
    private String errorDetail(String text) {
        Map<String, Object> parsed = tryParse(text);
        if (parsed != null) {
            String detail = firstNonEmpty(
                    nestedMessage(parsed, "error"),
                    nestedMessage(parsed, "output"),
                    text(parsed.get("message")),
                    text(parsed.get("msg")));
            if (filled(detail)) return detail;
        }
        if (filled(text)) return truncate(text, 300);
        return "";
    }

    private Map<String, Object> tryParse(String text) {
        if (!filled(text)) return null;
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nestedMessage(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map)) return "";
        Object message = ((Map<?, ?>) value).get("message");
        return message == null ? "" : String.valueOf(message);
    }

    // ================================================================ 小工具

    /** 额外参数是 JSON 对象时原样并进请求体，其它情况按 Python 的文案报错。 */
    private void applyExtraParams(Map<String, Object> body, String extraParams) {
        if (!filled(extraParams)) return;
        Object parsed;
        try {
            parsed = json.readValue(extraParams, Object.class);
        } catch (Exception err) {
            throw new IllegalArgumentException("额外参数不是合法 JSON：" + err.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("额外参数必须是 JSON 对象，例如 {\"seed\": 12345}");
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
            body.put(String.valueOf(entry.getKey()), entry.getValue());
        }
    }

    private IllegalArgumentException doubaoNotConnected() {
        return new IllegalArgumentException(
                "Java 版没有接入本地豆包客户端桥，这条豆包视频协议暂时不可用；请改用智谱 / 火山方舟 / 阿里云百炼的视频模型");
    }

    private static String joinUrl(String baseUrl, String suffix) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + suffix;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception err) {
            throw new IllegalArgumentException("无法编码任务 ID：" + value);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception err) {
            return String.valueOf(value);
        }
    }

    private static String message(Throwable err) {
        String text = err.getMessage();
        return text == null || text.trim().isEmpty() ? err.getClass().getSimpleName() : text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean filled(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 与 Python 的 {@code int(float(v))} 一致：允许 "5" 与 "5.0"。 */
    private static int toInt(Object value) {
        String text = text(value).trim();
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException err) {
            throw new IllegalArgumentException("视频参数必须是数字：" + text);
        }
    }
}

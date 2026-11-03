package com.lumenforge.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 图片生成客户端：按协议分流到 8 种厂商接口。
 *
 * <p>这是 Python 版 {@code app/providers/imagegen.py} 的移植。协议差异都收在这里：
 * 方舟/兼容接口走一次 JSON POST；智谱没有 n 参数要多张就串行请求；即梦与万相是
 * 异步任务要提交后轮询；混元与海螺同步返回；Cloudflare 走 multipart 且要缩参考图。</p>
 *
 * <p>所有失败都抛 {@link ImageGenException}，message 是可直接展示给用户的中文文案。</p>
 */
@Component
public class ImageGenerationClient {
    private static final Logger LOG = LoggerFactory.getLogger(ImageGenerationClient.class);

    /** 单张图片下载上限 40 MB（与前端提示一致）。 */
    private static final int MAX_DOWNLOAD_BYTES = 40 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(3);

    private static final Pattern DATA_URI = Pattern.compile("^data:([^;,]+);base64,(.*)$", Pattern.DOTALL);
    private static final Pattern HTTP_URL = Pattern.compile("^https?:", Pattern.CASE_INSENSITIVE);

    private final RestTemplate http;
    private final RestTemplate downloader;
    private final ObjectMapper json;

    public ImageGenerationClient(RestTemplateBuilder builder, ObjectMapper json) {
        this.json = json;
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(20))
                .setReadTimeout(REQUEST_TIMEOUT)
                .build();
        this.downloader = builder
                .setConnectTimeout(Duration.ofSeconds(20))
                .setReadTimeout(DOWNLOAD_TIMEOUT)
                .build();
    }

    // ================================================================ 入口

    /**
     * 提交一次生成请求，返回 {@code [{url}]} 或 {@code [{b64}]} 列表。
     *
     * <p>参考图已由上层读成 data URI 填进 {@link ImageRequest.Ref}，这里不碰磁盘。</p>
     */
    public List<Map<String, Object>> generate(ImageRequest request) {
        String protocol = trim(request.getProtocol());
        List<ImageRequest.Ref> snapshots = request.getRefs();
        List<ImageRequest.Ref> urlRefs = request.urlRefs();
        List<ImageRequest.Ref> fileRefs = request.fileRefs();

        if (!request.getModelName().trim().isEmpty() || !protocol.isEmpty()) {
            // 与 Python 一致：model 与 apiKey 都必须有
        }
        if (request.getModelName() == null || request.getModelName().trim().isEmpty()) {
            throw new ImageGenException("这个图片模型还没有填写模型 ID");
        }
        if (request.getApiKey() == null || request.getApiKey().trim().isEmpty()) {
            throw new ImageGenException("图片模型「" + request.getDisplayName() + "」还没有填写 API Key");
        }

        if (!snapshots.isEmpty()) {
            if (!supportsImageInput(request)) {
                if (ImageProtocols.ZHIPU.equals(protocol)) {
                    throw new ImageGenException(
                            "智谱 CogView 系列（当前「" + request.getModelName() + "」）接口没有参考图字段，做不到图生图 —— 参考图发不出去。"
                                    + "要「照参考图画」，请换成支持参考图的模型：通义万相 wan2.7-image-pro / 腾讯混元 hy-image-v3.5-preview / 即梦 jimeng_i2i_v30。");
                }
                throw new ImageGenException("图片模型「" + request.getDisplayName()
                        + "」未开启图生图，请在模型管理里勾选「支持图生图」或换一个模型");
            }
            if (snapshots.size() > ImageProtocols.MAX_REFS) {
                throw new ImageGenException("参考图最多 " + ImageProtocols.MAX_REFS
                        + " 张，当前 " + snapshots.size() + " 张");
            }
        }

        if (ImageProtocols.JIMENG.equals(protocol)) {
            return generateJimeng(request, urlRefs, fileRefs);
        }
        if (ImageProtocols.WANXIANG.equals(protocol)) {
            return generateWanxiang(request, snapshots, urlRefs, fileRefs);
        }
        if (ImageProtocols.HUNYUAN.equals(protocol)) {
            return generateHunyuan(request, urlRefs, fileRefs);
        }
        if (ImageProtocols.MINIMAX.equals(protocol)) {
            return generateMinimax(request, snapshots);
        }
        if (ImageProtocols.CLOUDFLARE.equals(protocol)) {
            return generateCloudflare(request, urlRefs, fileRefs);
        }
        if (ImageProtocols.ZHIPU.equals(protocol) && !snapshots.isEmpty()) {
            throw new ImageGenException("智谱 CogView 接口不支持参考图，没法图生图，请换方舟或兼容协议的模型");
        }

        // ---------------------------------------------------------- 方舟 / OpenAI 兼容
        if (!urlRefs.isEmpty()) {
            throw new ImageGenException("这个协议的参考图走本机文件 base64，链接图目前只有即梦和通义万相支持");
        }
        List<String> refsData = dataUris(fileRefs);
        if (!fileRefs.isEmpty() && refsData.isEmpty()) {
            throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
        }

        String url = ImageProtocols.joinUrl(request.getBaseUrl(), "/images/generations");
        // 智谱接口没有「一次出多张」的参数，要多张就串行请求几次
        int times = ImageProtocols.ZHIPU.equals(protocol) ? clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT) : 1;
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        for (int round = 0; round < times; round++) {
            Map<String, Object> body = buildRequestBody(request,
                    times > 1 ? 1 : request.getCount(), refsData);
            Map<String, Object> parsed = postJson(url, request.getApiKey(), body);
            List<Map<String, Object>> items = extractItems(parsed);
            if (items.isEmpty()) {
                throw new ImageGenException("接口没有返回图片数据：" + truncate(writeJson(parsed), 300));
            }
            all.addAll(items);
        }
        return all;
    }

    // ================================================================ 即梦

    private List<Map<String, Object>> generateJimeng(ImageRequest request,
                                                     List<ImageRequest.Ref> urlRefs,
                                                     List<ImageRequest.Ref> fileRefs) {
        if (request.getAkSecret() == null || request.getAkSecret().trim().isEmpty()) {
            throw new ImageGenException("即梦模型「" + request.getDisplayName() + "」还没有填写 AccessKeySecret");
        }
        List<String> refUrls = new ArrayList<String>();
        for (ImageRequest.Ref ref : urlRefs) if (!trim(ref.getUrl()).isEmpty()) refUrls.add(ref.getUrl().trim());
        List<String> refB64 = fileRefs.isEmpty()
                ? new ArrayList<String>()
                : dataUris(fileRefs.subList(0, Math.min(1, fileRefs.size())));
        if (!fileRefs.isEmpty() && refB64.isEmpty()) {
            throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
        }
        int times = clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int round = 0; round < times; round++) {
            out.addAll(generateOneJimeng(request, refUrls, refB64));
        }
        return out;
    }

    private List<Map<String, Object>> generateOneJimeng(ImageRequest request,
                                                        List<String> refUrls, List<String> refB64) {
        List<String> urls = refUrls.size() > 1 ? refUrls.subList(0, 1) : refUrls;
        List<String> b64 = refB64.size() > 1 ? refB64.subList(0, 1) : refB64;
        boolean hasImage = !urls.isEmpty() || !b64.isEmpty();
        String reqKey = hasImage ? ImageProtocols.JIMENG_I2I_REQ_KEY : ImageProtocols.JIMENG_REQ_KEY;
        if (hasImage && request.getPrompt().trim().isEmpty()) {
            throw new ImageGenException("即梦图生图必须写提示词（告诉它要怎么改这张图）");
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("req_key", reqKey);
        body.put("prompt", request.getPrompt());
        if (hasImage) {
            // 图生图 3.0 只收 1 张图：公网链接走 image_urls，本机图走 binary_data_base64
            if (!urls.isEmpty()) {
                body.put("image_urls", urls);
            } else {
                List<String> plain = new ArrayList<String>();
                for (String uri : b64) plain.add(stripDataUri(uri));
                body.put("binary_data_base64", plain);
            }
            // 出图尺寸跟随底图，不下发 width/height
        } else {
            int[] wh = ImageProtocols.parseJimengSize(request.getSize());
            if (wh != null) {
                body.put("width", wh[0]);
                body.put("height", wh[1]);
            }
            // 一张一提交，强制单图出图，避免平台按组图逻辑处理
            body.put("force_single", true);
        }

        Map<String, Object> submit;
        try {
            submit = volcPost(ImageProtocols.JIMENG_ACTION_SUBMIT, body, request);
        } catch (ImageGenException err) {
            // 图生图 3.0 是单独开通的服务：没开通时签名没问题，平台只甩 401 Access Denied
            String message = err.getMessage() == null ? "" : err.getMessage();
            if (hasImage && Pattern.compile("401|Access\\s*Denied", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                throw new ImageGenException(
                        "即梦「图生图 3.0」这个账号还没开通（平台返回 401 Access Denied）："
                                + "去火山引擎控制台的「智能视觉 → 即梦AI」开通图生图 3.0；"
                                + "或者去掉参考图改用文生图（文生图 4.0 是通的）", err.getCode(), err.getRaw());
            }
            throw err;
        }

        Object code = submit.get("code");
        Map<String, Object> data = asMap(submit.get("data"));
        String taskId = str(data.get("task_id"));
        if (!"10000".equals(String.valueOf(code)) || taskId.isEmpty()) {
            throw new ImageGenException("即梦提交任务失败（code=" + code + "）："
                    + (str(submit.get("message")).isEmpty() ? truncate(writeJson(submit), 300) : str(submit.get("message"))),
                    asInt(code, null), submit);
        }

        long deadline = System.currentTimeMillis() + ImageProtocols.JIMENG_POLL_TIMEOUT_MS;
        while (true) {
            sleep(ImageProtocols.JIMENG_POLL_INTERVAL_MS);
            Map<String, Object> query = new LinkedHashMap<String, Object>();
            query.put("req_key", reqKey);
            query.put("task_id", taskId);
            Map<String, Object> result = volcPost(ImageProtocols.JIMENG_ACTION_QUERY, query, request);
            Map<String, Object> resultData = asMap(result.get("data"));
            String status = str(resultData.get("status")).toLowerCase();

            if ("done".equals(status)) {
                if (!"10000".equals(String.valueOf(result.get("code")))) {
                    throw new ImageGenException("即梦任务完成但返回异常（code=" + result.get("code") + "）："
                            + str(result.get("message")), asInt(result.get("code"), null), result);
                }
                List<Map<String, Object>> images = extractJimengImages(resultData);
                if (images.isEmpty()) {
                    throw new ImageGenException("即梦任务没有返回图片：" + truncate(writeJson(result), 300));
                }
                return images;
            }
            if ("not_found".equals(status) || "expired".equals(status)) {
                throw new ImageGenException("即梦任务" + ("expired".equals(status) ? "已过期" : "不存在")
                        + "（task_id=" + taskId + "）");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new ImageGenException("即梦任务等待超时（task_id=" + taskId
                        + "，最后状态 " + (status.isEmpty() ? "未知" : status) + "）");
            }
        }
    }

    private List<Map<String, Object>> extractJimengImages(Map<String, Object> data) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        collectJimeng(data, out);
        Object resp = data.get("resp_data");
        if (resp instanceof String && !((String) resp).trim().isEmpty()) {
            Map<String, Object> nested = tryParse((String) resp);
            if (nested != null) collectJimeng(nested, out);
        } else if (resp instanceof Map) {
            collectJimeng(asMap(resp), out);
        }
        return out;
    }

    private void collectJimeng(Map<String, Object> node, List<Map<String, Object>> out) {
        if (node == null) return;
        Object urls = node.get("image_urls");
        if (urls instanceof List) {
            for (Object item : (List<?>) urls) {
                if (item instanceof String && !((String) item).isEmpty()) out.add(single("url", (String) item));
            }
        }
        Object base64List = node.get("binary_data_base64");
        if (base64List instanceof List) {
            for (Object item : (List<?>) base64List) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    out.add(single("b64", stripDataUri((String) item)));
                }
            }
        }
    }

    /** 火山 V4 签名 + JSON POST。 */
    private Map<String, Object> volcPost(String action, Map<String, Object> body, ImageRequest request) {
        Map<String, String> query = VolcSigner.actionQuery(action, ImageProtocols.JIMENG_API_VERSION);
        String canonicalQuery = VolcSigner.canonicalQuery(query);
        String url = "https://" + ImageProtocols.JIMENG_HOST + "/?" + canonicalQuery;
        String payload = writeJson(body);
        String xDate = VolcSigner.utcXDate();
        String authorization = VolcSigner.authorization("POST", ImageProtocols.JIMENG_HOST, "/", query,
                payload, request.getApiKey(), request.getAkSecret(),
                ImageProtocols.JIMENG_REGION, ImageProtocols.JIMENG_SERVICE, xDate);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("host", ImageProtocols.JIMENG_HOST);
        headers.set("x-date", xDate);
        headers.set("authorization", authorization);

        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST,
                    new HttpEntity<String>(payload, headers), String.class);
            return requireJson(response.getBody(), "即梦");
        } catch (RestClientResponseException err) {
            String text = safeBody(err);
            Map<String, Object> parsed = tryParse(text);
            String detail = firstNonEmpty(nested(parsed, "error", "message"),
                    str(parsed == null ? null : parsed.get("message")),
                    truncate(text, 300));
            throw new ImageGenException("即梦接口返回 " + err.getRawStatusCode() + "：" + detail,
                    err.getRawStatusCode(), parsed);
        } catch (ResourceAccessException err) {
            throw new ImageGenException(timeoutOrConnect(err, "即梦接口"));
        }
    }

    // ================================================================ 通义万相

    private List<Map<String, Object>> generateWanxiang(ImageRequest request,
                                                       List<ImageRequest.Ref> snapshots,
                                                       List<ImageRequest.Ref> urlRefs,
                                                       List<ImageRequest.Ref> fileRefs) {
        String modelId = trim(request.getModelName());
        boolean textToImage = ImageProtocols.isWanxiangTextToImage(modelId);

        if (snapshots.isEmpty()) {
            if (!textToImage) {
                throw new ImageGenException("通义万相这个模型需要至少 1 张参考图（请切到图生图模式并添加参考图），"
                        + "或把模型换成文生图模型（如 wan2.2-t2i-flash）");
            }
            return generateWanxiangTextToImage(request);
        }
        if (textToImage) {
            throw new ImageGenException("这个通义万相模型是文生图专用，请去掉参考图，或换成 wan2.6-image 这类图生图模型");
        }

        if (ImageProtocols.isWanxiangMultimodal(modelId)) {
            List<String> refs = new ArrayList<String>();
            for (ImageRequest.Ref ref : urlRefs) if (!trim(ref.getUrl()).isEmpty()) refs.add(ref.getUrl().trim());
            List<String> fileUris = dataUris(fileRefs);
            if (!fileRefs.isEmpty() && fileUris.isEmpty()) {
                throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
            }
            refs.addAll(fileUris);
            return generateWanxiangMultimodal(request, refs);
        }

        ImageRequest.Ref first = snapshots.get(0);
        String baseImage;
        if (!trim(first.getUrl()).isEmpty()) {
            baseImage = first.getUrl().trim();
        } else {
            String dataUri = first.getDataUri();
            if (dataUri == null || dataUri.trim().isEmpty()) {
                throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
            }
            baseImage = dataUri;
        }
        return generateWanxiangImageToImage(request, baseImage);
    }

    private List<Map<String, Object>> generateWanxiangImageToImage(ImageRequest request, String baseImage) {
        int n = clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT);
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("function", "description_edit");
        input.put("prompt", request.getPrompt());
        input.put("base_image_url", baseImage);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("n", n);
        parameters.put("watermark", request.watermarkEnabled());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("input", input);
        body.put("parameters", parameters);
        // 额外参数按根层合并（可整体覆盖 input / parameters 里的字段切换功能）
        mergeExtra(body, request, "{\"input\":{\"function\":\"remove_watermark\"}}");

        Map<String, Object> submit = dashscopeRequest(request, "POST",
                "/api/v1/services/aigc/image2image/image-synthesis", body, true);
        String taskId = str(asMap(submit.get("output")).get("task_id"));
        if (taskId.isEmpty()) {
            throw new ImageGenException("通义万相提交任务失败：" + firstNonEmpty(
                    str(submit.get("message")), str(submit.get("code")), truncate(writeJson(submit), 300)),
                    asInt(submit.get("code"), null), submit);
        }
        return awaitWanxiang(request, taskId);
    }

    private List<Map<String, Object>> generateWanxiangTextToImage(ImageRequest request) {
        int n = clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT);
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("prompt", request.getPrompt());
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("n", n);
        parameters.put("watermark", request.watermarkEnabled());
        int[] wh = ImageProtocols.parseWxh(request.getSize());
        if (wh != null) parameters.put("size", wh[0] + "*" + wh[1]);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("input", input);
        body.put("parameters", parameters);
        mergeExtra(body, request, "{\"parameters\":{\"size\":\"1024*1024\"}}");

        Map<String, Object> submit = dashscopeRequest(request, "POST",
                "/api/v1/services/aigc/text2image/image-synthesis", body, true);
        String taskId = str(asMap(submit.get("output")).get("task_id"));
        if (taskId.isEmpty()) {
            throw new ImageGenException("通义万相提交任务失败：" + firstNonEmpty(
                    str(submit.get("message")), str(submit.get("code")), truncate(writeJson(submit), 300)),
                    asInt(submit.get("code"), null), submit);
        }
        return awaitWanxiang(request, taskId);
    }

    private List<Map<String, Object>> awaitWanxiang(ImageRequest request, String taskId) {
        long deadline = System.currentTimeMillis() + ImageProtocols.WANXIANG_POLL_TIMEOUT_MS;
        while (true) {
            sleep(ImageProtocols.WANXIANG_POLL_INTERVAL_MS);
            String path = "/api/v1/tasks/" + urlEncode(taskId);
            Map<String, Object> query = dashscopeRequest(request, "GET", path, null, false);
            Map<String, Object> output = asMap(query.get("output"));
            String status = str(output.get("task_status")).toUpperCase();

            if ("SUCCEEDED".equals(status)) {
                List<String> urls = extractWanxiangUrls(output);
                if (urls.isEmpty()) {
                    throw new ImageGenException("通义万相任务没有返回图片：" + truncate(writeJson(query), 300));
                }
                List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
                for (String url : urls) items.add(single("url", url));
                return items;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                throw new ImageGenException("通义万相任务" + ("FAILED".equals(status) ? "失败" : "已取消") + "："
                        + firstNonEmpty(str(output.get("message")), str(query.get("message")), status), null, query);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new ImageGenException("通义万相任务等待超时（task_id=" + taskId
                        + "，最后状态 " + (status.isEmpty() ? "未知" : status) + "）");
            }
        }
    }

    private List<Map<String, Object>> generateWanxiangMultimodal(ImageRequest request, List<String> refs) {
        List<String> picked = refs.size() > ImageProtocols.MAX_REFS ? refs.subList(0, ImageProtocols.MAX_REFS) : refs;
        if (picked.isEmpty()) {
            throw new ImageGenException("万相图像编辑需要至少 1 张参考图");
        }
        int n = clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT);
        List<Object> content = new ArrayList<Object>();
        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("text", request.getPrompt());
        content.add(textPart);
        for (String uri : picked) {
            Map<String, Object> imagePart = new LinkedHashMap<String, Object>();
            imagePart.put("image", uri);
            content.add(imagePart);
        }
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", content);
        List<Object> messages = new ArrayList<Object>();
        messages.add(message);

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("messages", messages);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("n", n);
        parameters.put("watermark", request.watermarkEnabled());
        if (!request.getSize().isEmpty()) parameters.put("size", request.getSize());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("input", input);
        body.put("parameters", parameters);
        mergeExtra(body, request, "{\"parameters\":{\"size\":\"2K\"}}");

        Map<String, Object> result = dashscopeRequest(request, "POST",
                "/api/v1/services/aigc/multimodal-generation/generation", body, false);
        List<String> urls = extractWanxiangUrls(asMap(result.get("output")));
        if (urls.isEmpty()) {
            throw new ImageGenException("万相没有返回图片：" + firstNonEmpty(
                    str(result.get("message")), str(result.get("code")), truncate(writeJson(result), 300)),
                    asInt(result.get("code"), null), result);
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String url : urls) items.add(single("url", url));
        return items;
    }

    private List<String> extractWanxiangUrls(Map<String, Object> output) {
        List<String> out = new ArrayList<String>();
        Object results = output.get("results");
        if (results instanceof List) {
            for (Object entry : (List<?>) results) {
                if (entry instanceof Map) {
                    String url = str(asMap(entry).get("url"));
                    if (!url.isEmpty()) out.add(url);
                }
            }
        }
        Object choices = output.get("choices");
        if (choices instanceof List) {
            for (Object entry : (List<?>) choices) {
                if (!(entry instanceof Map)) continue;
                Object content = asMap(asMap(entry).get("message")).get("content");
                if (!(content instanceof List)) continue;
                for (Object part : (List<?>) content) {
                    if (part instanceof Map) {
                        String image = str(asMap(part).get("image"));
                        if (!image.isEmpty()) out.add(image);
                    }
                }
            }
        }
        return out;
    }

    /** 向百炼发一个 Bearer 鉴权请求；asyncFlag 时带 X-DashScope-Async: enable。 */
    private Map<String, Object> dashscopeRequest(ImageRequest request, String method, String path,
                                                 Map<String, Object> body, boolean asyncFlag) {
        String base = trim(request.getBaseUrl());
        if (base.isEmpty()) base = ImageProtocols.WANXIANG_BASE;
        String url = ImageProtocols.joinUrl(base, path);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + request.getApiKey());
        if (asyncFlag) headers.set("X-DashScope-Async", "enable");
        String payload = body == null ? null : writeJson(body);

        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.resolve(method),
                    new HttpEntity<String>(payload, headers), String.class);
            return requireJson(response.getBody(), "通义万相");
        } catch (RestClientResponseException err) {
            String text = safeBody(err);
            Map<String, Object> parsed = tryParse(text);
            String detail = firstNonEmpty(str(parsed == null ? null : parsed.get("message")),
                    str(parsed == null ? null : parsed.get("code")), truncate(text, 300));
            throw new ImageGenException("通义万相接口返回 " + err.getRawStatusCode() + "：" + detail,
                    err.getRawStatusCode(), parsed);
        } catch (ResourceAccessException err) {
            throw new ImageGenException(timeoutOrConnect(err, "通义万相接口"));
        }
    }

    // ================================================================ 混元

    private List<Map<String, Object>> generateHunyuan(ImageRequest request,
                                                      List<ImageRequest.Ref> urlRefs,
                                                      List<ImageRequest.Ref> fileRefs) {
        String prompt = request.getPrompt().trim();
        if (prompt.isEmpty()) throw new ImageGenException("混元生图必须填写提示词");

        String base = trim(request.getBaseUrl());
        if (base.isEmpty()) base = ImageProtocols.HUNYUAN_BASE;
        boolean v35 = request.getModelName() != null && request.getModelName().contains("v3.5");
        String endpoint = ImageProtocols.joinUrl(base, v35
                ? "/v1/wand/hunyuan-image/v35-generation"
                : "/v1/wand/hunyuan-image/v3-generation");

        List<String> refs = new ArrayList<String>();
        for (ImageRequest.Ref ref : urlRefs) if (!trim(ref.getUrl()).isEmpty()) refs.add(ref.getUrl().trim());
        int take = Math.min(3, fileRefs.size());
        List<String> fileUris = dataUris(fileRefs.subList(0, take));
        if (!fileRefs.isEmpty() && fileUris.isEmpty()) {
            throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
        }
        refs.addAll(fileUris);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("prompt", prompt);
        List<String> picked = refs.size() > 3 ? refs.subList(0, 3) : refs;
        if (!picked.isEmpty()) body.put("images", picked);
        String colonSize = ImageProtocols.normalizeColonSize(request.getSize());
        if (!colonSize.isEmpty()) body.put("size", colonSize);
        mergeExtra(body, request, "{\"seed\":1234,\"revise\":true}");

        Map<String, Object> parsed = postJson(endpoint, request.getApiKey(), body);
        List<Map<String, Object>> items = extractItems(parsed);
        // v3.5 走 OpenAI 结构，图片藏在 choices[0].delta.image.url
        if (items.isEmpty()) {
            Object choices = parsed.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Map<String, Object> choice = asMap(((List<?>) choices).get(0));
                String url = firstNonEmpty(
                        str(asMap(asMap(choice.get("delta")).get("image")).get("url")),
                        str(asMap(asMap(choice.get("message")).get("image")).get("url")));
                if (!url.isEmpty()) items.add(single("url", url));
            }
        }
        if (items.isEmpty()) {
            throw new ImageGenException("混元生图没有返回图片：" + firstNonEmpty(
                    str(asMap(parsed.get("error")).get("message")), str(parsed.get("message")),
                    truncate(writeJson(parsed), 300)), null, parsed);
        }
        return items;
    }

    // ================================================================ 海螺

    private List<Map<String, Object>> generateMinimax(ImageRequest request,
                                                      List<ImageRequest.Ref> snapshots) {
        String prompt = request.getPrompt().trim();
        if (prompt.isEmpty()) throw new ImageGenException("海螺生图必须填写提示词");

        String base = trim(request.getBaseUrl());
        if (base.isEmpty()) base = ImageProtocols.MINIMAX_BASE;

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("prompt", prompt);
        body.put("response_format", "url");
        body.put("n", clamp(request.getCount(), 1, 9));
        body.put("aigc_watermark", request.watermarkEnabled());

        Map<String, Object> parsedSize = ImageProtocols.parseSizeOrRatio(request.getSize());
        if (parsedSize.get("width") != null) {
            body.put("width", parsedSize.get("width"));
            body.put("height", parsedSize.get("height"));
        } else if (parsedSize.get("aspectRatio") != null) {
            body.put("aspect_ratio", parsedSize.get("aspectRatio"));
        }

        if (!snapshots.isEmpty()) {
            ImageRequest.Ref first = snapshots.get(0);
            String ref;
            if (!trim(first.getUrl()).isEmpty()) {
                ref = first.getUrl().trim();
            } else {
                ref = first.getDataUri();
                if (ref == null || ref.trim().isEmpty()) {
                    throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
                }
            }
            Map<String, Object> subject = new LinkedHashMap<String, Object>();
            subject.put("type", "character");
            subject.put("image_file", ref);
            List<Object> subjectList = new ArrayList<Object>();
            subjectList.add(subject);
            body.put("subject_reference", subjectList);
        }
        mergeExtra(body, request, "{\"prompt_optimizer\":true,\"seed\":1234}");

        Map<String, Object> parsed = postJson(ImageProtocols.joinUrl(base, "/v1/image_generation"),
                request.getApiKey(), body);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<String, Object> data = asMap(parsed.get("data"));
        Object urls = data.get("image_urls");
        if (urls instanceof List) {
            for (Object entry : (List<?>) urls) if (entry != null) items.add(single("url", String.valueOf(entry)));
        }
        Object base64List = data.get("image_base64");
        if (base64List instanceof List) {
            for (Object entry : (List<?>) base64List) if (entry != null) items.add(single("b64", String.valueOf(entry)));
        }
        if (items.isEmpty()) {
            throw new ImageGenException("海螺生图没有返回图片：" + firstNonEmpty(
                    str(asMap(parsed.get("base_resp")).get("status_msg")),
                    str(asMap(parsed.get("error")).get("message")),
                    truncate(writeJson(parsed), 300)), null, parsed);
        }
        return items;
    }

    // ================================================================ Cloudflare

    private List<Map<String, Object>> generateCloudflare(ImageRequest request,
                                                         List<ImageRequest.Ref> urlRefs,
                                                         List<ImageRequest.Ref> fileRefs) {
        if (request.getAccountId() == null || request.getAccountId().trim().isEmpty()) {
            throw new ImageGenException("Cloudflare 模型「" + request.getDisplayName() + "」还没有填写 Account ID");
        }
        if (!urlRefs.isEmpty()) {
            throw new ImageGenException("Cloudflare 的参考图走本机文件的字节流，链接图请先下载后再以「上传参考图」方式添加");
        }
        List<String> uris = dataUris(fileRefs);
        if (!fileRefs.isEmpty() && uris.isEmpty()) {
            throw new ImageGenException("参考图文件已被删除，请重新添加参考图");
        }

        String base = trim(request.getBaseUrl());
        if (base.isEmpty()) base = ImageProtocols.CLOUDFLARE_BASE;
        String url = ImageProtocols.stripTrailingSlash(base) + "/client/v4/accounts/"
                + urlEncode(request.getAccountId()) + "/ai/run/" + request.getModelName();

        Map<String, Object> extra = readExtraParams(request.getExtraParams(), "{\"guidance\":3.5,\"seed\":12345}");
        int[] wh = ImageProtocols.parseWxh(request.getSize());
        int times = clamp(request.getCount(), 1, ImageProtocols.MAX_COUNT);
        List<String> picked = uris.size() > ImageProtocols.CLOUDFLARE_MAX_REFS
                ? uris.subList(0, ImageProtocols.CLOUDFLARE_MAX_REFS) : uris;
        List<ShrunkRef> prepared = new ArrayList<ShrunkRef>();
        for (String uri : picked) prepared.add(shrinkCloudflareRef(uri));

        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        for (int round = 0; round < times; round++) {
            Map<String, String> form = new LinkedHashMap<String, String>();
            form.put("prompt", request.getPrompt());
            if (wh != null) {
                form.put("width", String.valueOf(wh[0]));
                form.put("height", String.valueOf(wh[1]));
            }
            // 逃生舱：额外的表单字段（guidance / seed 等），原样并入
            if (extra != null) {
                for (Map.Entry<String, Object> entry : extra.entrySet()) {
                    Object value = entry.getValue();
                    form.put(entry.getKey(), value instanceof String ? (String) value : writeJson(value));
                }
            }
            List<ShrunkRef> files = prepared;
            all.addAll(postCloudflare(url, request.getApiKey(), form, files));
        }
        return all;
    }

    /** FLUX.2 硬性要求参考图小于 512×512，本机图多半超限。已合规或解不开时原样返回。 */
    private ShrunkRef shrinkCloudflareRef(String uri) {
        byte[] buffer = new byte[0];
        String mime = "image/png";
        try {
            String base64 = stripDataUri(uri);
            buffer = Base64.getDecoder().decode(base64);
            String declared = dataUriMime(uri);
            if (!declared.isEmpty()) mime = declared;
        } catch (Exception err) {
            LOG.debug("Cloudflare 参考图 base64 解码失败，按原图发送", err);
            return new ShrunkRef(buffer, mime, extForMime(mime));
        }
        if (buffer.length == 0) return new ShrunkRef(buffer, mime, extForMime(mime));

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(buffer));
            if (image == null) return new ShrunkRef(buffer, mime, extForMime(mime));
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= ImageProtocols.CLOUDFLARE_REF_MAX_EDGE && height <= ImageProtocols.CLOUDFLARE_REF_MAX_EDGE) {
                return new ShrunkRef(buffer, mime, extForMime(mime));
            }
            double scale = (double) ImageProtocols.CLOUDFLARE_REF_MAX_EDGE / Math.max(width, height);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = resized.createGraphics();
            try {
                graphics.drawImage(image.getScaledInstance(targetWidth, targetHeight, java.awt.Image.SCALE_SMOOTH),
                        0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", out);
            byte[] data = out.toByteArray();
            if (data.length > 0) return new ShrunkRef(data, "image/png", "png");
        } catch (Exception err) {
            LOG.warn("[imagegen] Cloudflare 参考图压缩失败，按原图发送：{}", err.toString());
        }
        return new ShrunkRef(buffer, mime, extForMime(mime));
    }

    private List<Map<String, Object>> postCloudflare(String url, String apiKey,
                                                     Map<String, String> form, List<ShrunkRef> files) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<String, Object>();
        for (Map.Entry<String, String> entry : form.entrySet()) parts.add(entry.getKey(), entry.getValue());
        int index = 0;
        for (final ShrunkRef file : files) {
            final String name = "ref" + index + "." + file.extension;
            ByteArrayResource resource = new ByteArrayResource(file.buffer) {
                @Override public String getFilename() { return name; }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(file.mimeType));
            parts.add("input_image_" + index, new HttpEntity<ByteArrayResource>(resource, fileHeaders));
            index++;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("authorization", "Bearer " + apiKey);

        try {
            ResponseEntity<byte[]> response = http.exchange(url, HttpMethod.POST,
                    new HttpEntity<MultiValueMap<String, Object>>(parts, headers), byte[].class);
            String contentType = response.getHeaders().getContentType() == null
                    ? "" : response.getHeaders().getContentType().toString();
            byte[] body = response.getBody();
            if (Pattern.compile("^image/", Pattern.CASE_INSENSITIVE).matcher(contentType).find()) {
                if (body == null || body.length == 0) throw new ImageGenException("Cloudflare 返回了空图片");
                List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
                items.add(single("b64", Base64.getEncoder().encodeToString(body)));
                return items;
            }
            String text = body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
            List<Map<String, Object>> items = extractCloudflareItems(tryParse(text));
            if (items.isEmpty()) throw new ImageGenException("Cloudflare 没有返回图片数据：" + truncate(text, 300));
            return items;
        } catch (RestClientResponseException err) {
            throw new ImageGenException(describeCloudflareError(err.getRawStatusCode(), safeBody(err)),
                    err.getRawStatusCode(), null);
        } catch (ResourceAccessException err) {
            throw new ImageGenException(timeoutOrConnect(err, "Cloudflare 接口"));
        }
    }

    private List<Map<String, Object>> extractCloudflareItems(Object parsed) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (!(parsed instanceof Map) && !(parsed instanceof List)) return items;
        Object root = parsed;
        if (parsed instanceof Map) {
            Object result = ((Map<?, ?>) parsed).get("result");
            if (result != null) root = result;
        }
        List<Object> nodes = new ArrayList<Object>();
        if (root instanceof List) {
            for (Object node : (List<?>) root) nodes.add(node);
        } else {
            nodes.add(root);
        }
        for (Object node : nodes) {
            if (node == null) continue;
            if (node instanceof String) {
                pushCloudflareItem(items, node);
                continue;
            }
            if (!(node instanceof Map)) continue;
            Map<String, Object> map = asMap(node);
            Object value = map.get("image");
            if (value == null) value = map.get("images");
            if (value == null) value = map.get("b64_json");
            if (value == null) value = map.get("data");
            if (value instanceof List) {
                for (Object entry : (List<?>) value) pushCloudflareItem(items, entry);
            } else if (value != null) {
                pushCloudflareItem(items, value);
            }
        }
        return items;
    }

    private void pushCloudflareItem(List<Map<String, Object>> items, Object value) {
        if (value == null) return;
        if (value instanceof Map) {
            Map<String, Object> map = asMap(value);
            String url = str(map.get("url"));
            if (!url.isEmpty()) {
                items.add(single("url", url));
            } else if (map.get("image") != null) {
                pushCloudflareItem(items, map.get("image"));
            } else if (map.get("b64_json") != null) {
                items.add(single("b64", str(map.get("b64_json"))));
            }
            return;
        }
        String text = String.valueOf(value);
        if (HTTP_URL.matcher(text).find()) {
            items.add(single("url", text));
        } else {
            items.add(single("b64", text.replaceAll("^data:[^;]+;base64,", "")));
        }
    }

    private String describeCloudflareError(int status, String text) {
        String detail = text == null ? "" : text;
        Map<String, Object> parsed = tryParse(detail);
        if (parsed != null) {
            Object errors = parsed.get("errors");
            String message = "";
            if (errors instanceof List && !((List<?>) errors).isEmpty()) {
                message = str(asMap(((List<?>) errors).get(0)).get("message"));
            }
            if (message.isEmpty()) message = str(asMap(parsed.get("error")).get("message"));
            if (message.isEmpty()) message = str(parsed.get("message"));
            if (!message.isEmpty()) detail = message;
        }
        if (status == 400 && Pattern.compile("image|shape|tensor|512", Pattern.CASE_INSENSITIVE).matcher(detail).find()) {
            return "Cloudflare 拒绝了参考图（HTTP 400）：官方要求每张参考图小于 512×512、最多 4 张。原始信息：" + detail;
        }
        if (status == 401) return "Cloudflare 鉴权失败（HTTP 401）：API Token 无效或已过期。原始信息：" + detail;
        if (status == 403) return "Cloudflare 权限不足（HTTP 403）：Token 需要勾选「Workers AI」读取权限。原始信息：" + detail;
        if (status == 429) {
            return "Cloudflare 限流或免费额度用尽（HTTP 429）：免费档每天 10,000 Neurons，00:00 UTC 重置。原始信息：" + detail;
        }
        return "Cloudflare 接口返回 " + status + "：" + detail;
    }

    // ================================================================ 通用请求

    /** 组装请求体；模型配置里的额外参数最后合并，可覆盖任何默认字段。 */
    private Map<String, Object> buildRequestBody(ImageRequest request, int count, List<String> refs) {
        int n = clamp(count, 1, ImageProtocols.MAX_COUNT);
        List<String> picked = refs.size() > ImageProtocols.MAX_REFS ? refs.subList(0, ImageProtocols.MAX_REFS) : refs;
        String protocol = trim(request.getProtocol());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", request.getModelName());
        body.put("prompt", request.getPrompt());
        // 智谱接口没有 response_format 字段，只认 data:[{url}]，不下发
        if (!ImageProtocols.ZHIPU.equals(protocol)) body.put("response_format", "url");

        if (ImageProtocols.ARK.equals(protocol)) {
            if (!request.getSize().isEmpty()) body.put("size", request.getSize());
            body.put("watermark", request.watermarkEnabled());
            // 参考图：单张用字符串、多张用数组，两种写法方舟都收
            if (!picked.isEmpty() && !Boolean.FALSE.equals(request.getSupportsImageInput())) {
                body.put("image", picked.size() == 1 ? picked.get(0) : picked);
            }
            // 只有要多张时才带组图参数：部分模型（如 5.0 pro）不支持该字段，传了会报错
            if (n > 1) {
                body.put("sequential_image_generation", "auto");
                body.put("max_images", n);
            }
        } else if (ImageProtocols.ZHIPU.equals(protocol)) {
            // 智谱只认「宽x高」枚举，填了枚举外的值会 400 拒收，干脆不下发
            if (!request.getSize().isEmpty() && ImageProtocols.ZHIPU_IMAGE_SIZES.contains(request.getSize())) {
                body.put("size", request.getSize());
            }
            // watermark_enabled 默认不加水印；关掉了才显式传 true
            if (request.watermarkEnabled()) body.put("watermark_enabled", true);
        } else {
            // openai 兼容协议：勾选了「支持图生图」时，把 base64 参考图放进 image 字段
            if (Boolean.TRUE.equals(request.getSupportsImageInput()) && !picked.isEmpty()) {
                body.put("image", picked.size() == 1 ? picked.get(0) : picked);
            }
            if (!request.getSize().isEmpty()) body.put("size", request.getSize());
            body.put("n", n);
        }

        Map<String, Object> extra = readExtraParams(request.getExtraParams(), null);
        if (extra != null) body.putAll(extra);
        return body;
    }

    private Map<String, Object> postJson(String url, String apiKey, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + apiKey);
        String payload = writeJson(body);

        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST,
                    new HttpEntity<String>(payload, headers), String.class);
            return requireJson(response.getBody(), "接口");
        } catch (RestClientResponseException err) {
            String text = safeBody(err);
            Map<String, Object> parsed = tryParse(text);
            String detail = firstNonEmpty(
                    str(asMap(parsed == null ? null : parsed.get("error")).get("message")),
                    str(parsed == null ? null : parsed.get("message")),
                    str(parsed == null ? null : parsed.get("msg")),
                    truncate(text, 300),
                    "HTTP " + err.getRawStatusCode());
            throw new ImageGenException(describeImageError(err.getRawStatusCode(), detail),
                    err.getRawStatusCode(), parsed);
        } catch (ResourceAccessException err) {
            throw new ImageGenException(timeoutOrConnect(err, "图片生成接口"));
        }
    }

    /**
     * 把厂商返回的英文错误翻成用户看得懂的中文。
     *
     * <p>重点是火山系（方舟 ark / 即梦）的内容审核 400，以及欠费时也返回 403 的坑。</p>
     */
    private String describeImageError(int status, String detail) {
        String text = detail == null ? "" : detail;
        // 火山方舟欠费时也返回 403，原文是 "account has an overdue balance"。
        // 不先认出来的话会被下面的 403 分支译成「鉴权失败」，把人引到 API Key 上去白折腾。
        if (Pattern.compile("overdue|欠费|余额不足|arrears|insufficient\\s+balance", Pattern.CASE_INSENSITIVE)
                .matcher(text).find()) {
            return "账户已欠费：请求被拒（HTTP " + status + "），请到火山方舟控制台「费用中心」充值后再试。原始信息：" + text;
        }
        if (status == 400 && Pattern.compile("sensitive|moderation|risk|safety|nsfw|内容|审核", Pattern.CASE_INSENSITIVE)
                .matcher(text).find()) {
            return "参考图没通过内容审核，接口拒绝了这次请求。可以试试："
                    + "① 换一张参考图（截图边角、水印、人脸特写都容易被误判）；"
                    + "② 关掉参考图，改成纯文生图；"
                    + "③ 换一个审核更宽松的模型 / 服务商。";
        }
        if (status == 401 || status == 403) {
            return "鉴权失败（HTTP " + status + "）：API Key 可能无效、过期，或没有开通该模型。原始信息：" + text;
        }
        if (status == 429) {
            return "请求太频繁或额度用尽（HTTP 429），稍等一会儿再试。原始信息：" + text;
        }
        return "接口返回 " + status + "：" + text;
    }

    /** 兼容解析 {data:[...]} / {images:[...]} 两种列表形态。 */
    private List<Map<String, Object>> extractItems(Map<String, Object> parsed) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (parsed == null) return items;
        Object list = parsed.get("data");
        if (!(list instanceof List)) list = parsed.get("images");
        if (!(list instanceof List)) return items;
        for (Object entry : (List<?>) list) {
            if (entry == null) continue;
            if (entry instanceof String) {
                String url = (String) entry;
                if (!url.isEmpty()) items.add(single("url", url));
            } else if (entry instanceof Map) {
                Map<String, Object> map = asMap(entry);
                String url = str(map.get("url"));
                if (!url.isEmpty()) {
                    items.add(single("url", url));
                } else {
                    String base64 = str(map.get("b64_json"));
                    if (!base64.isEmpty()) items.add(single("b64", base64));
                }
            }
        }
        return items;
    }

    /** 模型是否支持图生图：智谱恒 false；显式 true 为 true；否则仅方舟在未显式 false 时算 true。 */
    private boolean supportsImageInput(ImageRequest request) {
        String protocol = trim(request.getProtocol());
        if (ImageProtocols.ZHIPU.equals(protocol)) return false;
        if (Boolean.TRUE.equals(request.getSupportsImageInput())) return true;
        return ImageProtocols.ARK.equals(protocol) && !Boolean.FALSE.equals(request.getSupportsImageInput());
    }

    // ================================================================ 结果落地

    /** 下载一张结果图：优先 base64，其次公网 URL。 */
    public DownloadedImage download(Map<String, Object> item) {
        if (item == null) throw new ImageGenException("没有可下载的图片数据");

        String base64 = str(item.get("b64"));
        if (!base64.isEmpty()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (Exception err) {
                throw new ImageGenException("下载图片失败：base64 解码失败 " + err.getMessage());
            }
            String mime = str(item.get("mimeType"));
            return new DownloadedImage(bytes, DownloadedImage.guessExtension("", mime));
        }

        String url = str(item.get("url"));
        if (!HTTP_URL.matcher(url).find()) throw new ImageGenException("图片地址不是 http(s) 链接，无法下载");
        try {
            ResponseEntity<byte[]> response = downloader.exchange(url, HttpMethod.GET,
                    new HttpEntity<Void>(new HttpHeaders()), byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) throw new ImageGenException("下载到的内容为空");
            if (bytes.length > MAX_DOWNLOAD_BYTES) throw new ImageGenException("生成图片超过 40 MB 限制");
            String contentType = response.getHeaders().getContentType() == null
                    ? "" : response.getHeaders().getContentType().toString();
            return new DownloadedImage(bytes, DownloadedImage.guessExtension(url, contentType));
        } catch (RestClientResponseException err) {
            throw new ImageGenException("下载图片失败：HTTP " + err.getRawStatusCode());
        } catch (ResourceAccessException err) {
            if (isTimeout(err)) throw new ImageGenException("下载图片超时");
            throw new ImageGenException("下载图片失败：" + err.getMessage());
        }
    }

    // ================================================================ 小工具

    private List<String> dataUris(List<ImageRequest.Ref> refs) {
        List<String> out = new ArrayList<String>();
        for (ImageRequest.Ref ref : refs) {
            String uri = ref.getDataUri();
            if (uri != null && !uri.trim().isEmpty()) out.add(uri);
        }
        return out;
    }

    private void mergeExtra(Map<String, Object> body, ImageRequest request, String example) {
        Map<String, Object> extra = readExtraParams(request.getExtraParams(), example);
        if (extra != null) body.putAll(extra);
    }

    /** 解析模型的额外参数；空返回 null，格式错误抛中文提示。 */
    private Map<String, Object> readExtraParams(String raw, String example) {
        String text = trim(raw);
        if (text.isEmpty()) return null;
        Object parsed;
        try {
            parsed = json.readValue(text, Object.class);
        } catch (Exception err) {
            throw new ImageGenException("额外参数不是合法 JSON：" + err.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new ImageGenException("额外参数必须是 JSON 对象，例如 "
                    + (example == null ? "{\"seed\": 12345}" : example));
        }
        return asMap(parsed);
    }

    private Map<String, Object> requireJson(String text, String vendor) {
        Map<String, Object> parsed = tryParse(text);
        if (parsed == null) {
            throw new ImageGenException(vendor + "返回的不是 JSON：" + truncate(text, 300));
        }
        return parsed;
    }

    private Map<String, Object> tryParse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            Object parsed = json.readValue(text, Object.class);
            return parsed instanceof Map ? asMap(parsed) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception err) {
            throw new ImageGenException("序列化请求体失败：" + err.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private String nested(Map<String, Object> root, String outer, String inner) {
        if (root == null) return "";
        return str(asMap(root.get(outer)).get(inner));
    }

    private String safeBody(RestClientResponseException err) {
        try {
            return err.getResponseBodyAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String timeoutOrConnect(ResourceAccessException err, String vendor) {
        return isTimeout(err) ? "请求超时：" + vendor + "长时间没有响应" : "无法连接" + vendor + "：" + err.getMessage();
    }

    private boolean isTimeout(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) return true;
            if (cause instanceof java.io.InterruptedIOException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer asInt(Object value, Integer fallback) {
        if (value == null) return fallback;
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String truncate(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
        return "";
    }

    private Map<String, Object> single(String key, String value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(key, value);
        return out;
    }

    private String extForMime(String mime) {
        String value = mime == null ? "" : mime.toLowerCase();
        if (value.contains("jpeg")) return "jpg";
        if (value.contains("webp")) return "webp";
        return "png";
    }

    private String stripDataUri(String uri) {
        return DownloadedImage.stripDataUri(uri);
    }

    private String dataUriMime(String uri) {
        return DownloadedImage.dataUriMime(uri);
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception err) {
            return value;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new ImageGenException("等待图片生成结果时被中断");
        }
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }

    /** 已按 512 边压缩过的参考图。 */
    private static class ShrunkRef {
        final byte[] buffer;
        final String mimeType;
        final String extension;

        ShrunkRef(byte[] buffer, String mimeType, String extension) {
            this.buffer = buffer;
            this.mimeType = mimeType;
            this.extension = extension;
        }
    }
}

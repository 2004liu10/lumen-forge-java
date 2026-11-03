package com.lumenforge.service.canvas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.mapper.canvas.CanvasMapper;
import com.lumenforge.entity.account.Account;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.mapper.account.AccountMapper;
import com.lumenforge.provider.RunningHubClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布配置服务：提供画布 CRUD、参数快照维护和本地素材管理。
 * 可变的字段定义和值放在 JSON 配置中，是为了兼容厂商不断变化的节点 schema。
 */
@Service
@Transactional
public class CanvasServiceImpl implements CanvasService {
    private static final String DEFAULT_BASE_URL = "https://www.runninghub.cn";
    private final CanvasMapper repository;
    private final AccountMapper accounts;
    private final CredentialCipher cipher;
    private final RunningHubClient runningHub;
    private final ObjectMapper objectMapper;
    private final java.nio.file.Path storageRoot;

    public CanvasServiceImpl(CanvasMapper repository, AccountMapper accounts, CredentialCipher cipher,
                         RunningHubClient runningHub, ObjectMapper objectMapper,
                         @Value("${app.storage.root:./data}") String storageRoot) {
        this.repository = repository;
        this.accounts = accounts;
        this.cipher = cipher;
        this.runningHub = runningHub;
        this.objectMapper = objectMapper;
        this.storageRoot = java.nio.file.Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Canvas canvas : repository.findAll()) result.add(view(canvas));
        return result;
    }

    public Map<String, Object> create(Map<String, Object> request) {
        String name = string(request.get("name"), "画布");
        Canvas canvas = new Canvas(name, "app", DEFAULT_BASE_URL, "", "", null, true, 2);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("fields", new ArrayList<Object>());
        config.put("values", new LinkedHashMap<String, Object>());
        config.put("assets", new LinkedHashMap<String, Object>());
        config.put("removeWatermark", true);
        config.put("webFallback", true);
        config.put("polishAppId", "");
        config.put("webUrl", "");
        config.put("instanceType", "");
        config.put("extraParams", "");
        canvas.setConfigJson(write(config));
        return view(repository.save(canvas));
    }

    /** 只修改本次提交的字段；values 使用合并语义以匹配现有前端 PATCH 行为。 */
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Canvas canvas = require(id);
        Map<String, Object> config = config(canvas);
        String name = canvas.getName();
        String mode = canvas.getMode();
        String baseUrl = canvas.getBaseUrl();
        String appId = canvas.getAppId();
        String appName = canvas.getAppName();
        String accountId = canvas.getAccountId();
        boolean autoRetry = canvas.isAutoRetry();
        int maxAttempts = canvas.getMaxAttempts();

        if (patch.containsKey("name") && !string(patch.get("name"), "").trim().isEmpty()) name = string(patch.get("name"), name).trim();
        if (patch.containsKey("mode") && ("app".equals(patch.get("mode")) || "workflow".equals(patch.get("mode")))) {
            mode = String.valueOf(patch.get("mode"));
            if (!mode.equals(canvas.getMode())) {
                appId = "";
                appName = "";
                config.put("fields", new ArrayList<Object>());
                config.put("values", new LinkedHashMap<String, Object>());
                config.put("assets", new LinkedHashMap<String, Object>());
            }
        }
        if (patch.containsKey("baseUrl")) baseUrl = string(patch.get("baseUrl"), DEFAULT_BASE_URL).trim();
        if (patch.containsKey("appId")) appId = string(patch.get("appId"), "").trim();
        if (patch.containsKey("appName")) appName = string(patch.get("appName"), "");
        if (patch.containsKey("accountId")) accountId = string(patch.get("accountId"), "").trim();
        if (patch.containsKey("autoRetry")) autoRetry = bool(patch.get("autoRetry"));
        if (patch.containsKey("maxAttempts")) maxAttempts = Math.max(1, integer(patch.get("maxAttempts"), 2));

        for (String key : new String[]{"fields", "assets", "removeWatermark", "polishAppId", "webFallback", "webUrl", "instanceType", "extraParams"}) {
            if (patch.containsKey(key)) config.put(key, patch.get(key));
        }
        if (patch.get("values") instanceof Map) {
            Map<String, Object> merged = objectMap(config.get("values"));
            merged.putAll(objectMap(patch.get("values")));
            config.put("values", merged);
        }
        canvas.update(name, mode, baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl, appId, appName,
                accountId.isEmpty() ? null : accountId, autoRetry, maxAttempts);
        canvas.setConfigJson(write(config));
        return view(repository.save(canvas));
    }

    @Transactional(readOnly = true)
    public Canvas require(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("画布不存在"));
    }

    public Map<String, Object> setInspectedFields(String id, String appId, String appName, String instanceType, List<Map<String, Object>> fields) {
        Canvas canvas = require(id);
        Map<String, Object> config = config(canvas);
        config.put("fields", fields);
        config.put("instanceType", instanceType == null ? "" : instanceType);
        Map<String, Object> values = objectMap(config.get("values"));
        for (Map<String, Object> field : fields) {
            String key = string(field.get("nodeId"), "") + "::" + string(field.get("fieldName"), "");
            Object defaultValue = field.get("defaultValue");
            if (!values.containsKey(key) && defaultValue != null && !String.valueOf(defaultValue).isEmpty()) values.put(key, defaultValue);
        }
        config.put("values", values);
        canvas.update(canvas.getName(), canvas.getMode(), canvas.getBaseUrl(), appId,
                appName, canvas.getAccountId(), canvas.isAutoRetry(), canvas.getMaxAttempts());
        canvas.setConfigJson(write(config));
        return view(repository.save(canvas));
    }

    public Map<String, Object> view(Canvas canvas) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", canvas.getId());
        result.put("name", canvas.getName());
        result.put("mode", canvas.getMode());
        result.put("baseUrl", canvas.getBaseUrl());
        result.put("appId", canvas.getAppId());
        result.put("appName", canvas.getAppName());
        result.put("accountId", canvas.getAccountId());
        result.put("autoRetry", canvas.isAutoRetry());
        result.put("maxAttempts", canvas.getMaxAttempts());
        result.put("createdAt", canvas.getCreatedAt() == null ? Instant.now().toEpochMilli() : canvas.getCreatedAt().toEpochMilli());
        result.putAll(config(canvas));
        return result;
    }

    public void delete(String id) {
        if (!repository.existsById(id)) throw new IllegalArgumentException("画布不存在");
        repository.deleteById(id);
    }

    /**
     * 解析画布参数：工作流从用户提供的 api.json 提取输入，AI 应用从平台查询 schema。
     * 将平台调用放在业务层，避免 Controller 承担账号选择和外部服务编排。
     */
    public Map<String, Object> inspect(String id, Map<String, Object> request) {
        Canvas canvas = require(id);
        String appId = string(request.get("appId"), canvas.getAppId()).trim();
        if (appId.isEmpty()) throw new IllegalArgumentException("请填写 webappId / workflowId");
        List<Map<String, Object>> fields;
        String appName;
        String instanceType = "";
        if ("workflow".equals(canvas.getMode())) {
            String workflowJson = string(request.get("workflowJson"), "").trim();
            if (workflowJson.isEmpty()) throw new IllegalArgumentException("工作流模式需要粘贴导出的 api.json 内容");
            fields = parseWorkflow(workflowJson);
            if (fields.isEmpty()) throw new IllegalArgumentException("未能从该 JSON 中解析出可填参数");
            appName = "工作流 " + appId;
        } else {
            Account account = chooseAccount(request.get("accountId"), canvas.getAccountId());
            String apiKey = cipher.decrypt(account.getApiKeyCiphertext());
            String baseUrl = "ai".equals(account.getSite()) ? "https://www.runninghub.ai" : canvas.getBaseUrl();
            Map<String, Object> inspected = runningHub.inspectApp(baseUrl, apiKey, appId);
            fields = (List<Map<String, Object>>) inspected.get("fields");
            appName = string(inspected.get("appName"), "应用 " + appId);
            instanceType = string(inspected.get("instanceType"), "");
            if (fields.isEmpty()) throw new IllegalArgumentException("应用「" + appName + "」没有返回可填参数");
        }
        return setInspectedFields(id, appId, appName, instanceType, fields);
    }

    private Account chooseAccount(Object requestedId, String canvasAccountId) {
        String id = string(requestedId, canvasAccountId);
        if (!id.isEmpty()) {
            Account selected = accounts.findById(id).orElse(null);
            if (selected != null && selected.isEnabled() && selected.getApiKeyCiphertext() != null
                    && !selected.getApiKeyCiphertext().isEmpty()) return selected;
        }
        return accounts.findAll().stream()
                .filter(Account::isEnabled)
                .filter(account -> account.getApiKeyCiphertext() != null && !account.getApiKeyCiphertext().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先在「账号管理」中添加至少一个 API Key"));
    }

    private List<Map<String, Object>> parseWorkflow(String raw) {
        try {
            Object decoded = objectMapper.readValue(raw, Object.class);
            Map<?, ?> root = decoded instanceof Map ? (Map<?, ?>) decoded : new LinkedHashMap<Object, Object>();
            Object prompt = root.get("prompt");
            Map<?, ?> nodes = prompt instanceof Map ? (Map<?, ?>) prompt : root;
            List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
            for (Map.Entry<?, ?> entry : nodes.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<?, ?> node = (Map<?, ?>) entry.getValue();
                Object rawInputs = node.get("inputs");
                if (!(rawInputs instanceof Map)) continue;
                Map<?, ?> inputs = (Map<?, ?>) rawInputs;
                Map<?, ?> meta = node.get("_meta") instanceof Map ? (Map<?, ?>) node.get("_meta") : new LinkedHashMap<Object, Object>();
                String title = string(meta.get("title"), string(node.get("class_type"), ""));
                for (Map.Entry<?, ?> input : inputs.entrySet()) {
                    Object value = input.getValue();
                    if (value == null || value instanceof Map || value instanceof List) continue;
                    String fieldName = String.valueOf(input.getKey());
                    String lower = fieldName.toLowerCase();
                    // ComfyUI 导出的 API 工作流有时会把数字序列化成字符串；按节点/字段语义识别，避免数值节点收到文本。
                    String type = isNumericInput(string(node.get("class_type"), ""), fieldName, value)
                            ? "NUMBER" : value instanceof Boolean ? "LIST" : "STRING";
                    if (lower.contains("video")) type = "VIDEO";
                    else if (lower.contains("audio")) type = "AUDIO";
                    else if (lower.contains("image") || lower.contains("mask")) type = "IMAGE";
                    Map<String, Object> field = new LinkedHashMap<String, Object>();
                    field.put("nodeId", String.valueOf(entry.getKey()));
                    field.put("nodeName", string(node.get("class_type"), ""));
                    field.put("fieldName", fieldName);
                    field.put("fieldType", type);
                    field.put("description", title.isEmpty() ? fieldName : title + " · " + fieldName);
                    field.put("defaultValue", String.valueOf(value));
                    field.put("options", value instanceof Boolean
                            ? java.util.Arrays.asList(option("true"), option("false")) : new ArrayList<Object>());
                    fields.add(field);
                }
            }
            return fields;
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON 解析失败：" + exception.getMessage());
        }
    }

    /** 判断一个标量输入是否是数值，覆盖 VHS 和常见 ComfyUI 数值节点。 */
    private boolean isNumericInput(String classType, String fieldName, Object value) {
        if (value instanceof Number) return true;
        if (!(value instanceof String)) return false;
        String text = ((String) value).trim();
        if (text.isEmpty() || !text.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) return false;
        String name = fieldName.toLowerCase();
        String node = classType.toLowerCase();
        if (node.contains("int") || node.contains("float") || node.contains("number")) return true;
        return java.util.Arrays.asList("force_rate", "custom_width", "custom_height", "frame_load_cap",
                "skip_first_frames", "select_every_nth", "width", "height", "fps", "steps", "cfg",
                "seed", "denoise", "scale", "batch_size", "frame_rate", "duration", "strength",
                "threshold", "ratio", "count", "size").contains(name);
    }

    private Map<String, String> option(String value) {
        Map<String, String> option = new LinkedHashMap<String, String>();
        option.put("index", value);
        option.put("label", value);
        return option;
    }

    /** 按当前画布字段校验上传目标，再把文件存入受控 uploads 子目录。 */
    public Map<String, Object> uploadAsset(String id, String key, byte[] bytes, String originalName, String mimeType) {
        Canvas canvas = require(id);
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("没有收到文件内容");
        if (bytes.length > 256L * 1024L * 1024L) throw new IllegalArgumentException("素材大小不能超过 256 MB");
        Map<String, Object> config = config(canvas);
        boolean validFileField = false;
        for (Map<String, Object> field : maps(config.get("fields"))) {
            String fieldKey = string(field.get("nodeId"), "") + "::" + string(field.get("fieldName"), "");
            String type = string(field.get("fieldType"), "").toUpperCase();
            if (fieldKey.equals(key) && java.util.Arrays.asList("IMAGE", "VIDEO", "AUDIO").contains(type)) {
                validFileField = true;
                break;
            }
        }
        if (!validFileField) throw new IllegalArgumentException("该参数不是当前画布上的文件类型");
        String safeName = originalName == null || originalName.trim().isEmpty() ? "upload.bin" : originalName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        String ext = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf('.')).toLowerCase() : ".bin";
        String storedName = java.util.UUID.randomUUID().toString().replace("-", "") + ext;
        java.nio.file.Path uploadDir = storageRoot.resolve("uploads").normalize();
        java.nio.file.Path destination = uploadDir.resolve(storedName).normalize();
        if (!destination.startsWith(uploadDir)) throw new IllegalArgumentException("非法文件名");
        try {
            java.nio.file.Files.createDirectories(uploadDir);
            java.nio.file.Files.write(destination, bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("保存素材失败：" + exception.getMessage());
        }
        Map<String, Object> asset = new LinkedHashMap<String, Object>();
        asset.put("originalName", safeName);
        asset.put("localPath", destination.toString());
        asset.put("localUrl", "/files/uploads/" + storedName);
        asset.put("mimeType", mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
        asset.put("size", bytes.length);
        asset.put("uploadedAt", System.currentTimeMillis());
        Map<String, Object> assets = objectMap(config.get("assets"));
        assets.put(key, asset);
        config.put("assets", assets);
        canvas.setConfigJson(write(config));
        repository.save(canvas);
        return view(canvas);
    }

    public Map<String, Object> removeAsset(String id, String key) {
        Canvas canvas = require(id);
        Map<String, Object> config = config(canvas);
        Map<String, Object> assets = objectMap(config.get("assets"));
        Object removed = assets.remove(key);
        config.put("assets", assets);
        canvas.setConfigJson(write(config));
        repository.save(canvas);
        if (removed instanceof Map) {
            String path = string(((Map<?, ?>) removed).get("localPath"), "");
            if (!path.isEmpty()) {
                java.nio.file.Path file = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
                java.nio.file.Path uploadRoot = storageRoot.resolve("uploads").normalize();
                if (file.startsWith(uploadRoot)) {
                    try { java.nio.file.Files.deleteIfExists(file); } catch (Exception ignored) { }
                }
            }
        }
        return view(canvas);
    }

    /**
     * 额外参数：前端把整段 JSON 文本原样提交，这里只做一次语法校验。
     * Python 版要求必须是 JSON 数组（[{"name":"seed","value":12345}]），否则 RunningHub 会拒绝。
     */
    public Map<String, Object> setExtraParams(String id, Map<String, Object> request) {
        Canvas canvas = require(id);
        Map<String, Object> config = config(canvas);
        String raw = string(request.get("extraParams"), "");
        if (!raw.trim().isEmpty()) {
            Object parsed;
            try {
                parsed = objectMapper.readValue(raw, Object.class);
            } catch (Exception exception) {
                throw new IllegalArgumentException("额外参数格式错误：" + exception.getMessage());
            }
            if (!(parsed instanceof List)) {
                throw new IllegalArgumentException("额外参数必须是 JSON 数组，例如 [{\"name\":\"seed\",\"value\":12345}]");
            }
        }
        config.put("extraParams", raw);
        canvas.setConfigJson(write(config));
        return view(repository.save(canvas));
    }

    private Map<String, Object> config(Canvas canvas) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(canvas.getConfigJson(), new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
        } catch (Exception exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to persist canvas configuration", exception); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (value instanceof List) for (Object item : (List<?>) value) if (item instanceof Map) result.add((Map<String, Object>) item);
        return result;
    }

    private String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private boolean bool(Object value) { return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value)); }
    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException exception) { return fallback; }
    }
}





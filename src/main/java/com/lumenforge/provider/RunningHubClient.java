package com.lumenforge.provider;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RunningHub 协议适配器：集中封装 HTTP 路径、鉴权头、请求体和状态码。
 * 这样调度器只处理统一的 queued/running/success/failed 状态，不必散落平台协议细节。
 */
@Component
public class RunningHubClient {
    private final RestTemplate restTemplate;

    public RunningHubClient(org.springframework.boot.web.client.RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(java.time.Duration.ofSeconds(15))
                .setReadTimeout(java.time.Duration.ofSeconds(90))
                .build();
    }

    /** 读取 AI 应用字段，并转换成画布可以直接渲染的字段结构。 */
    public Map<String, Object> inspectApp(String baseUrl, String apiKey, String appId) {
        String url = UriComponentsBuilder.fromHttpUrl(trimBase(baseUrl) + "/api/webapp/apiCallDemo")
                .queryParam("apiKey", apiKey).queryParam("webappId", appId).build().encode().toUriString();
        Map response = restTemplate.getForObject(url, Map.class);
        requireCodeZero(response, "读取应用参数失败");
        Map data = asMap(response.get("data"));
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        Object rawFields = data.get("nodeInfoList");
        if (rawFields instanceof List) {
            for (Object item : (List<?>) rawFields) {
                if (item instanceof Map) fields.add(normalizeField((Map<?, ?>) item));
            }
        }
        String curl = string(data.get("curl"));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"instanceType\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(curl);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("appName", string(data.get("webappName")));
        result.put("instanceType", matcher.find() ? matcher.group(1) : "");
        result.put("fields", fields);
        return result;
    }

    /** 按画布模式选择 AI 应用或 ComfyUI 工作流的提交端点。 */
    public String createTask(String baseUrl, String apiKey, String mode, String appId,
                             String instanceType, List<Map<String, Object>> nodeInfoList) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("apiKey", apiKey);
        payload.put("nodeInfoList", nodeInfoList);
        if (instanceType != null && !instanceType.isEmpty()) payload.put("instanceType", instanceType);
        String path;
        if ("workflow".equals(mode)) {
            payload.put("workflowId", appId);
            path = "/task/openapi/create";
        } else {
            payload.put("webappId", appId);
            path = "/task/openapi/ai-app/run";
        }
        Map response = postJson(baseUrl, path, apiKey, payload);
        requireCodeZero(response, "提交 RunningHub 任务失败");
        Map data = asMap(response.get("data"));
        Object id = data.get("taskId");
        if (id == null || string(id).isEmpty()) throw new IllegalStateException("RunningHub 未返回 taskId");
        return String.valueOf(id);
    }

    /** 将 RunningHub 的 0/804/813/805 响应规整成内部任务状态。 */
    public Map<String, Object> queryOutputs(String baseUrl, String apiKey, String remoteTaskId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("apiKey", apiKey);
        payload.put("taskId", remoteTaskId);
        Map response = postJson(baseUrl, "/task/openapi/outputs", apiKey, payload);
        int code = integer(response.get("code"), -1);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (code == 804 || code == 0) {
            Object rawData = response.get("data");
            if (rawData instanceof List) {
                List<Map<String, Object>> outputs = new ArrayList<Map<String, Object>>();
                for (Object raw : (List<?>) rawData) {
                    if (!(raw instanceof Map)) continue;
                    Map item = (Map) raw;
                    String fileUrl = string(item.containsKey("fileUrl") ? item.get("fileUrl") : item.get("file_url"));
                    if (fileUrl.isEmpty()) continue;
                    Map<String, Object> output = new LinkedHashMap<String, Object>();
                    output.put("fileUrl", fileUrl);
                    output.put("fileType", string(item.get("fileType")).toLowerCase());
                    output.put("nodeId", string(item.get("nodeId")));
                    output.put("costTime", item.get("taskCostTime"));
                    outputs.add(output);
                }
                result.put("state", outputs.isEmpty() ? "running" : "success");
                result.put("outputs", outputs);
            } else {
                result.put("state", "running");
            }
            return result;
        }
        if (code == 813) {
            result.put("state", "queued");
            return result;
        }
        String message = string(response.get("msg"));
        if (code == 805) {
            Map data = asMap(response.get("data"));
            Map reason = asMap(data.get("failedReason"));
            String details = string(reason.get("node_name")) + " · " + string(reason.get("exception_message"));
            result.put("state", "failed");
            result.put("error", details.trim().equals("·") || details.trim().isEmpty() ? message : details);
            return result;
        }
        result.put("state", "failed");
        result.put("error", message.isEmpty() ? "未知错误（code=" + code + "）" : message + "（code=" + code + "）");
        return result;
    }

    /** 请求平台取消远端任务；本地取消状态由任务模块管理。 */
    public void cancelTask(String baseUrl, String apiKey, String remoteTaskId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("apiKey", apiKey);
        payload.put("taskId", remoteTaskId);
        postJson(baseUrl, "/task/openapi/cancel", apiKey, payload);
    }

    /** 先把本地素材上传到平台，返回的文件名再作为节点参数提交。 */
    public String uploadFile(String baseUrl, String apiKey, File file, String mimeType) {
        if (!file.isFile()) throw new IllegalArgumentException("上传素材不存在：" + file.getName());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<String, Object>();
        parts.add("apiKey", apiKey);
        parts.add("fileType", "input");
        FileSystemResource resource = new FileSystemResource(file);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mimeType == null || mimeType.isEmpty()
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mimeType));
        fileHeaders.setContentDispositionFormData("file", file.getName());
        parts.add("file", new HttpEntity<FileSystemResource>(resource, fileHeaders));
        Map response = restTemplate.postForObject(trimBase(baseUrl) + "/task/openapi/upload",
                new HttpEntity<MultiValueMap<String, Object>>(parts, headers), Map.class);
        requireCodeZero(response, "RunningHub 素材上传失败");
        Object data = response.get("data");
        if (data instanceof Map) {
            Map result = (Map) data;
            for (String key : Arrays.asList("fileName", "fileNameWithPath", "filePath")) {
                if (result.get(key) != null) return string(result.get(key));
            }
        }
        if (data instanceof String && !((String) data).isEmpty()) return (String) data;
        throw new IllegalStateException("RunningHub 上传接口未返回文件名");
    }

    private Map postJson(String baseUrl, String path, String apiKey, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return restTemplate.postForObject(trimBase(baseUrl) + path,
                new HttpEntity<Map<String, Object>>(payload, headers), Map.class);
    }

    private void requireCodeZero(Map response, String prefix) {
        if (response == null) throw new IllegalStateException(prefix + "：接口没有返回 JSON");
        if (integer(response.get("code"), -1) != 0) {
            String message = string(response.get("msg"));
            throw new IllegalStateException(message.isEmpty() ? prefix + "（code=" + response.get("code") + "）" : message);
        }
    }

    private Map<String, Object> normalizeField(Map<?, ?> item) {
        String type = string(item.get("fieldType")).trim().toUpperCase();
        String name = string(item.get("fieldName")).toLowerCase();
        if (Arrays.asList("COMBO", "ENUM", "SELECT").contains(type)) type = "LIST";
        if (Arrays.asList("INT", "INTEGER", "FLOAT", "DOUBLE").contains(type)) type = "NUMBER";
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) type = "LIST";
        if (type.isEmpty()) {
            if (item.get("fieldData") != null) type = "LIST";
            else if (name.contains("video")) type = "VIDEO";
            else if (name.contains("audio")) type = "AUDIO";
            else if (name.contains("image") || name.contains("mask")) type = "IMAGE";
            else type = "STRING";
        }
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("nodeId", string(item.get("nodeId")));
        field.put("nodeName", string(item.get("nodeName")));
        field.put("fieldName", string(item.get("fieldName")));
        field.put("fieldType", type);
        field.put("description", string(item.containsKey("description") ? item.get("description") : item.get("descriptionEn")));
        field.put("defaultValue", string(item.get("fieldValue")));
        field.put("options", parseOptions(item.get("fieldData"), type));
        return field;
    }

    private List<Map<String, String>> parseOptions(Object raw, String type) {
        if (!"LIST".equals(type) || raw == null) return Collections.emptyList();
        Object value = raw;
        if (raw instanceof String) {
            try { value = new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) raw, Object.class); }
            catch (Exception exception) { return Collections.emptyList(); }
        }
        if (!(value instanceof List)) return Collections.emptyList();
        List<?> list = (List<?>) value;
        if (!list.isEmpty() && list.get(0) instanceof String && list.size() > 1 && list.get(1) instanceof Map) {
            if ("BOOLEAN".equalsIgnoreCase(String.valueOf(list.get(0)))) {
                return Arrays.asList(option("true", "true（开启）"), option("false", "false（关闭）"));
            }
            return Collections.emptyList();
        }
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (Object item : list) {
            if (item == null) continue;
            if (item instanceof Map) {
                Map option = (Map) item;
                String index = string(option.containsKey("index") ? option.get("index") : option.containsKey("name") ? option.get("name") : option.get("value"));
                String label = string(option.containsKey("description") ? option.get("description") : option.containsKey("name") ? option.get("name") : index);
                result.add(option(index, label));
            } else result.add(option(String.valueOf(item), String.valueOf(item)));
        }
        return result;
    }

    private Map<String, String> option(String index, String label) {
        Map<String, String> option = new LinkedHashMap<String, String>();
        option.put("index", index);
        option.put("label", label);
        return option;
    }

    private String trimBase(String base) { return (base == null || base.trim().isEmpty() ? "https://www.runninghub.cn" : base.trim()).replaceAll("/+$", ""); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException exception) { return fallback; }
    }
    @SuppressWarnings("unchecked")
    private Map asMap(Object value) { return value instanceof Map ? (Map) value : Collections.emptyMap(); }
}





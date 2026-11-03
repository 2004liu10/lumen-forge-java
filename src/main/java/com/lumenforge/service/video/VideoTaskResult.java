package com.lumenforge.service.video;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频任务的运行时状态，序列化进 {@code video_tasks.result_json}。
 *
 * <p>对应 Python 版任务 dict 里除「模型/提示词/参数/状态/错误」之外的动态字段：
 * 轮询节奏、远端地址、本地产物、抽取出的尾帧。DDL 里 {@code result_json} 是可空的
 * LONGTEXT，所以这里所有字段都给默认值，缺字段时按默认解析。
 */
final class VideoTaskResult {
    /** 提交时的厂商标识；模型后来被删了也还能读出来。 */
    String provider = "";
    /** 首帧 / 尾帧的执行快照（路径给执行用，URL 给历史记录展示用）。 */
    Map<String, Object> input;
    Map<String, Object> endInput;
    Map<String, Object> video;
    Map<String, Object> lastFrame;
    String progress = "";
    String remoteVideoUrl = "";
    String remoteStatus = "";
    long nextPollAt;
    int pollErrors;
    boolean cancelRequested;
    List<String> triedAccounts = new ArrayList<String>();
    boolean watermarkStripped;
    Long startedAt;

    static VideoTaskResult read(ObjectMapper json, String raw) {
        VideoTaskResult state = new VideoTaskResult();
        if (raw == null || raw.trim().isEmpty()) return state;
        Map<String, Object> parsed;
        try {
            parsed = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return state;
        }
        if (parsed == null) return state;
        state.provider = text(parsed.get("provider"));
        state.input = cast(parsed.get("input"));
        state.endInput = cast(parsed.get("endInput"));
        state.video = cast(parsed.get("video"));
        state.lastFrame = cast(parsed.get("lastFrame"));
        state.progress = text(parsed.get("progress"));
        state.remoteVideoUrl = text(parsed.get("remoteVideoUrl"));
        state.remoteStatus = text(parsed.get("remoteStatus"));
        state.nextPollAt = asLong(parsed.get("nextPollAt"));
        state.pollErrors = (int) asLong(parsed.get("pollErrors"));
        state.cancelRequested = asBool(parsed.get("cancelRequested"));
        state.watermarkStripped = asBool(parsed.get("watermarkStripped"));
        Object started = parsed.get("startedAt");
        state.startedAt = started == null ? null : asLong(started);
        Object tried = parsed.get("triedAccounts");
        if (tried instanceof List) {
            for (Object item : (List<?>) tried) {
                if (item != null) state.triedAccounts.add(String.valueOf(item));
            }
        }
        return state;
    }

    String write(ObjectMapper json) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider", provider);
        out.put("input", input);
        out.put("endInput", endInput);
        out.put("video", video);
        out.put("lastFrame", lastFrame);
        out.put("progress", progress);
        out.put("remoteVideoUrl", remoteVideoUrl);
        out.put("remoteStatus", remoteStatus);
        out.put("nextPollAt", nextPollAt);
        out.put("pollErrors", pollErrors);
        out.put("cancelRequested", cancelRequested);
        out.put("triedAccounts", triedAccounts);
        out.put("watermarkStripped", watermarkStripped);
        out.put("startedAt", startedAt);
        try {
            return json.writeValueAsString(out);
        } catch (Exception err) {
            throw new IllegalStateException("无法序列化视频任务状态", err);
        }
    }

    private static Map<String, Object> cast(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }
}

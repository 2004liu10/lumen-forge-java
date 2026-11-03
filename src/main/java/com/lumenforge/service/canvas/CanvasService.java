package com.lumenforge.service.canvas;

import com.lumenforge.entity.canvas.Canvas;

import java.util.List;
import java.util.Map;

/** 画布业务能力接口：让 Controller 只负责 HTTP 参数绑定与响应。 */
public interface CanvasService {
    List<Map<String, Object>> list();
    Map<String, Object> create(Map<String, Object> request);
    Map<String, Object> update(String id, Map<String, Object> patch);
    Canvas require(String id);
    Map<String, Object> setInspectedFields(String id, String appId, String appName, String instanceType, List<Map<String, Object>> fields);
    Map<String, Object> view(Canvas canvas);
    void delete(String id);
    Map<String, Object> uploadAsset(String id, String key, byte[] bytes, String originalName, String mimeType);
    Map<String, Object> removeAsset(String id, String key);
    Map<String, Object> inspect(String id, Map<String, Object> request);
    /** 保存画布「额外参数」原始 JSON 文本（必须是 JSON 数组，提交时原样传给 RunningHub）。 */
    Map<String, Object> setExtraParams(String id, Map<String, Object> request);
}




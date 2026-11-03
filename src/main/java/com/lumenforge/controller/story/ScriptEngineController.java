package com.lumenforge.controller.story;

import com.lumenforge.service.story.StoryboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 脚本引擎 HTTP 模块：分镜面板上的「检测本地 Codex」按钮调它。
 *
 * <p>响应形状与 Python 版 {@code app/api/script_engine_api.py} 一致：
 * {@code {engine, available, exe, version, reason}}。
 * 探测本身出错（而不是探测不到）在 Python 里是 400，这里抛 IllegalArgumentException 即可。
 */
@RestController
@RequestMapping("/api")
public class ScriptEngineController {

    private final StoryboardService service;

    public ScriptEngineController(StoryboardService service) {
        this.service = service;
    }

    @GetMapping("/script-engine")
    public Map<String, Object> scriptEngine() {
        return service.scriptEngineInfo();
    }
}

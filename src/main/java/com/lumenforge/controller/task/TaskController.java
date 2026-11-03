package com.lumenforge.controller.task;

import com.lumenforge.service.task.TaskService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 生成任务 HTTP 模块：暴露运行、查询、取消、重试与远端结果恢复接口。 */
@RestController
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @PostMapping("/api/canvases/{canvasId}/run")
    public Map<String, Object> runCanvas(@PathVariable String canvasId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String accountId = body == null ? null : string(body.get("accountId"));
        return service.create(canvasId, accountId);
    }

    @PostMapping("/api/canvases/run-all")
    public Map<String, Object> runAll() { return service.createAll(); }

    @GetMapping("/api/tasks")
    public List<Map<String, Object>> list(@RequestParam(required = false) String canvasId,
                                          @RequestParam(required = false) String accountId,
                                          @RequestParam(defaultValue = "120") int limit) {
        return service.list(canvasId, accountId, limit);
    }

    @PostMapping("/api/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) { return service.cancel(id); }

    @PostMapping("/api/tasks/{id}/refresh-result")
    public Map<String, Object> refresh(@PathVariable String id) { return service.refreshResult(id); }

    @PostMapping("/api/tasks/import")
    public Map<String, Object> importRemote(@RequestBody Map<String, Object> body) {
        return service.importRemoteTask(string(body.get("remoteTaskId")), string(body.get("accountId")), string(body.get("canvasId")));
    }

    @PostMapping("/api/tasks/fetch-missing")
    public Map<String, Object> fetchMissing() { return service.fetchMissingResults(); }

    @PostMapping("/api/tasks/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) { return service.retry(id); }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/tasks/{id}")
    public Map<String, Object> delete(@PathVariable String id) { return service.delete(id); }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
}





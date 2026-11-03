package com.lumenforge.controller.topic;

import com.lumenforge.service.topic.TopicService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 话题库 HTTP 模块；归一化、去重与排序都交给 {@link TopicService}。
 *
 * <p>响应形状与 Python 版 {@code app/api/topics_api.py} 逐字段一致：
 * 话题写操作统一带 {@code topics} 与 {@code stats} 两个整库快照。
 */
@RestController
@RequestMapping("/api")
public class TopicController {

    private final TopicService service;

    public TopicController(TopicService service) {
        this.service = service;
    }

    @GetMapping("/topics")
    public Map<String, Object> list(@RequestParam(value = "q", required = false) String q) {
        return service.list(q);
    }

    @PostMapping("/topics")
    public Map<String, Object> add(@RequestBody(required = false) Map<String, Object> body) {
        return service.add(body);
    }

    @PostMapping("/topics/used")
    public Map<String, Object> markUsed(@RequestBody(required = false) Map<String, Object> body) {
        return service.markUsed(body);
    }

    /**
     * 删一条话题。Python 版这里抛的是 404（{@code not_found("这个话题不在库里")}），
     * 而 {@code GlobalExceptionHandler} 只把 IllegalArgumentException 翻成 400，
     * 所以 404 必须在控制器这一层自己产出，否则前端拿到的状态码就和契约不一致了。
     */
    @DeleteMapping("/topics/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.delete(id));
        } catch (IllegalArgumentException exception) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("error", exception.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
    }

    @DeleteMapping("/topics")
    public Map<String, Object> clear() {
        return service.clear();
    }
}

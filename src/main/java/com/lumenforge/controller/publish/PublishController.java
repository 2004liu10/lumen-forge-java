package com.lumenforge.controller.publish;

import com.lumenforge.service.publish.PublishService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发布记录 HTTP 模块；身份判定、查重与删除分析都在 {@link PublishService}。
 *
 * <p>响应形状与 Python 版 {@code app/api/publish_api.py} 逐字段一致。
 */
@RestController
@RequestMapping("/api")
public class PublishController {

    private final PublishService service;

    public PublishController(PublishService service) {
        this.service = service;
    }

    @GetMapping("/publish/records")
    public Map<String, Object> records() {
        return service.listRecords();
    }

    @GetMapping("/publish/works")
    public Map<String, Object> works() {
        return service.listWorks();
    }

    @PostMapping("/publish/check")
    public Map<String, Object> check(@RequestBody(required = false) Map<String, Object> body) {
        return service.check(body);
    }

    @PostMapping("/publish/filled")
    public Map<String, Object> filled(@RequestBody(required = false) Map<String, Object> body) {
        return service.markFilled(body);
    }

    @PostMapping("/publish/records/{id}/published")
    public Map<String, Object> published(@PathVariable String id) {
        return service.markPublished(id);
    }

    @PostMapping("/publish/published-by-path")
    public Map<String, Object> publishedByPath(@RequestBody(required = false) Map<String, Object> body) {
        return service.markPublishedByPath(body);
    }

    @PostMapping("/publish/records/{id}/unpublish")
    public Map<String, Object> unpublish(@PathVariable String id) {
        return service.unpublish(id);
    }

    @DeleteMapping("/publish/records/{id}")
    public Map<String, Object> deleteRecord(@PathVariable String id) {
        return service.removeRecord(id);
    }

    @DeleteMapping("/publish/records")
    public Map<String, Object> deleteAllRecords() {
        return service.clearRecords();
    }

    @PostMapping("/publish/deletable")
    public Map<String, Object> deletable(@RequestBody(required = false) Map<String, Object> body) {
        return service.analyzeDeletable(body);
    }

    @PostMapping("/publish/mark-deleted")
    public Map<String, Object> markDeleted(@RequestBody(required = false) Map<String, Object> body) {
        return service.markDeleted(body);
    }
}

package com.lumenforge.controller.story;

import com.lumenforge.service.story.StoryboardNotFoundException;
import com.lumenforge.service.story.StoryboardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜剧本 HTTP 模块；编剧本、出定妆照、批量出图都在 {@link StoryboardService}。
 *
 * <p>响应形状与 Python 版 {@code app/api/story_api.py} 逐字段一致。
 * 剧本不存在时 Python 抛的是 404（{@code not_found("这份剧本不存在")}），
 * 而 {@code GlobalExceptionHandler} 只把 IllegalArgumentException 翻成 400，
 * 所以 404 在这里自己产出；业务校验错误仍然是 IllegalArgumentException → 400。
 */
@RestController
@RequestMapping("/api")
public class StoryboardController {

    private final StoryboardService service;

    public StoryboardController(StoryboardService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 剧本模型

    @GetMapping("/story-model")
    public Map<String, Object> storyModel() {
        return service.storyModelSnapshot();
    }

    @PostMapping("/story-model")
    public Map<String, Object> saveStoryModel(@RequestBody(required = false) Map<String, Object> body) {
        return service.saveStoryModel(patchOf(body));
    }

    // ------------------------------------------------------------------ 剧本列表

    @GetMapping("/storyboards")
    public List<Map<String, Object>> listStoryboards() {
        return service.listBoards();
    }

    @PostMapping("/storyboards")
    public Map<String, Object> createStoryboard(@RequestBody(required = false) Map<String, Object> body) {
        return service.createBoard(patchOf(body));
    }

    @GetMapping("/storyboards/{boardId}")
    public ResponseEntity<Map<String, Object>> getStoryboard(@PathVariable String boardId) {
        try {
            return ResponseEntity.ok(service.board(boardId));
        } catch (StoryboardNotFoundException exception) {
            return notFound(exception);
        }
    }

    @DeleteMapping("/storyboards/{boardId}")
    public ResponseEntity<Map<String, Object>> deleteStoryboard(@PathVariable String boardId) {
        try {
            return ResponseEntity.ok(service.deleteBoard(boardId));
        } catch (StoryboardNotFoundException exception) {
            return notFound(exception);
        }
    }

    // ------------------------------------------------------------------ 出图

    @PostMapping("/storyboards/{boardId}/cast")
    public ResponseEntity<Map<String, Object>> cast(@PathVariable String boardId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.castCharacter(boardId, patchOf(body)));
        } catch (StoryboardNotFoundException exception) {
            return notFound(exception);
        }
    }

    @PostMapping("/storyboards/{boardId}/draw")
    public ResponseEntity<Map<String, Object>> draw(@PathVariable String boardId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.drawShots(boardId, patchOf(body)));
        } catch (StoryboardNotFoundException exception) {
            return notFound(exception);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** Python 的 {@code json_body} 把空体当空对象；这里保持一致，避免 service 里到处判 null。 */
    private static Map<String, Object> patchOf(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<String, Object>() : body;
    }

    private static ResponseEntity<Map<String, Object>> notFound(StoryboardNotFoundException exception) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}

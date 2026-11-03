package com.lumenforge.controller.canvasai;

import com.lumenforge.service.canvasai.CanvasChatService;
import com.lumenforge.service.canvasai.CanvasDirectorService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 画布 AI —— 编导拆分镜 + 右侧聊天。对应 Python 版 {@code app/api/canvas_ai.py}。
 *
 * <p>三条路由：
 * <ul>
 *   <li>{@code GET  /api/canvas/director} —— 当前引擎状态（前端进页面时探测一次）</li>
 *   <li>{@code POST /api/canvas/director} —— 一段文字 → 一份分镜（无上下文，纯函数）</li>
 *   <li>{@code POST /api/canvas/chat} —— 聊天：判意图、回一句话，必要时内部再走上面那条</li>
 * </ul>
 *
 * <p>原版这三条都 try/catch 后回 400 {@code {error}}，所以业务异常一律翻成 400 ——
 * 前端拿到的是人话（「先贴一段剧本，或者写一句题材」），不是 500 堆栈。
 * Java 侧靠 {@code GlobalExceptionHandler} 把 {@link IllegalArgumentException} 转成
 * 400 {@code {"error": "<message>"}}，所以这里<b>不要再自己包一层 catch</b>：
 * 包了反而会把 400 变回 500。
 */
@RestController
@RequestMapping("/api")
public class CanvasAiController {

    private final CanvasDirectorService director;
    private final CanvasChatService chat;

    public CanvasAiController(CanvasDirectorService director, CanvasChatService chat) {
        this.director = director;
        this.chat = chat;
    }

    /** 进画布页面时探测一次：画风候选、镜头数范围、剧本模型配没配好。 */
    @GetMapping("/canvas/director")
    public Map<String, Object> getDirector() {
        return director.directorInfo();
    }

    /**
     * 一段文字 → 一份分镜。
     *
     * <p>注意 {@code wantVideo} 的判法：Python 是 {@code body.get("wantVideo") is not False}，
     * 也就是「只有明确给了 false 才算不要视频」，没给、给 null、给字符串都当作要视频。
     */
    @PostMapping("/canvas/director")
    public Map<String, Object> postDirector(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new java.util.LinkedHashMap<String, Object>() : body;
        Object wantVideo = payload.get("wantVideo");
        return director.directStoryboard(
                payload.get("script"),
                payload.get("shotCount"),
                payload.get("style"),
                wantVideo == null || !Boolean.FALSE.equals(wantVideo),
                null,
                null);
    }

    /**
     * 聊一句：返回 {@code {reply, intent, plan?, edits?}}。
     *
     * <p>{@code photoIds} 是人物参考图的图库 id（前端先上传到图库，这里只收 id）。
     */
    @PostMapping("/canvas/chat")
    public Map<String, Object> postChat(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new java.util.LinkedHashMap<String, Object>() : body;
        return chat.chatWithCanvas(
                payload.get("messages"),
                payload.get("scene"),
                payload.get("prefs"),
                payload.get("photoIds"));
    }
}

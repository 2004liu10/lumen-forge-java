package com.lumenforge.service.video;

import java.util.List;
import java.util.Map;

/**
 * 视频生成业务入口。
 *
 * <p>对应 Python 版 {@code app/video_jobs.py} + {@code app/api/video_api.py}：
 * 模型配置、输入图库、异步任务三段，以及后台提交 / 轮询 / 下载的执行循环。
 * 所有校验错误都抛 {@link IllegalArgumentException}，由 {@code GlobalExceptionHandler} 翻成 400。
 */
public interface VideoGenerationService {

    // ---------------------------------------------------------------- 模型

    List<Map<String, Object>> listModels();

    Map<String, Object> createModel(Map<String, Object> body);

    Map<String, Object> updateModel(String id, Map<String, Object> body);

    Map<String, Object> deleteModel(String id);

    // ---------------------------------------------------------------- 输入图库

    List<Map<String, Object>> listInputs();

    /** 裸二进制上传；{@code mimeType} 只在扩展名判不出来时兜底。 */
    Map<String, Object> uploadInput(byte[] buffer, String originalName, String mimeType);

    Map<String, Object> deleteInput(String id);

    // ---------------------------------------------------------------- 任务

    List<Map<String, Object>> listTasks(int limit);

    Map<String, Object> createTask(Map<String, Object> body);

    Map<String, Object> retryTask(String id);

    Map<String, Object> cancelTask(String id);

    Map<String, Object> deleteTask(String id);

    /** 把某张已生成的图片转成视频输入图，并记回图片任务的 {@code sentToVideo}。 */
    Map<String, Object> imageTaskToVideo(String taskId, Map<String, Object> body);

    // ---------------------------------------------------------------- 调度

    /** 定时驱动的提交 / 轮询 / 下载，一次只推进一条任务。 */
    void processQueue();
}

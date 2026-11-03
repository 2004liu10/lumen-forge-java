package com.lumenforge.service.image;

import java.util.List;
import java.util.Map;

/** 图片生成用例；Controller 不直接触碰 provider 或持久化实体。 */
public interface ImageGenerationService {

    // ---------------------------------------------------------------- 模型管理
    List<Map<String, Object>> listModels();

    Map<String, Object> createModel(Map<String, Object> body);

    Map<String, Object> updateModel(String id, Map<String, Object> body);

    Map<String, Object> deleteModel(String id);

    // ---------------------------------------------------------------- 任务
    List<Map<String, Object>> listTasks(int limit);

    Map<String, Object> createTask(Map<String, Object> body);

    Map<String, Object> retryTask(String id);

    Map<String, Object> cancelTask(String id);

    Map<String, Object> deleteTask(String id);

    // ---------------------------------------------------------------- 参考图库
    List<Map<String, Object>> listRefs();

    /** 上传一张参考图（原始字节流，文件名走 query 或请求头）。 */
    Map<String, Object> uploadRef(byte[] buffer, String originalName, String mimeType);

    Map<String, Object> deleteRef(String id);

    /** 把任务里的一张生成结果收进参考图库。 */
    Map<String, Object> taskImageToRef(String taskId, Map<String, Object> body);

    /** 复用某条任务用过的参考图：图库还在的直接复用，图库丢了但文件还在的重新登记。 */
    Map<String, Object> reuseTaskRefs(String taskId);

    /** 把任务里的一张生成结果送进画布的图片输入参数。 */
    Map<String, Object> taskImageToCanvas(String taskId, Map<String, Object> body);

    /** 把网页端抓到的临时图片导入成一条成功任务（豆包/浏览器桥的落点）。 */
    Map<String, Object> importImage(Map<String, Object> body);

    // ---------------------------------------------------------------- 队列
    void processQueue();
}

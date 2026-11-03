package com.lumenforge.service.ncanvas;

import java.util.List;
import java.util.Map;

/**
 * 节点画布业务能力：让 Controller 只负责 HTTP 参数绑定与响应。
 *
 * <p>对应 Python 版 {@code app/node_canvas.py} 里那批 {@code create_graph / update_graph /
 * delete_graph / save_chat / delete_chat / export_graph / import_graph}。
 */
public interface NodeCanvasService {

    /** 取一张图，不存在抛 {@link com.lumenforge.entity.ncanvas.NodeCanvasNotFoundException}。 */
    Map<String, Object> requireGraph(String canvasId);

    /** 新建一张空画布，返回整张图（前端拿到 id 后立刻开始画）。 */
    Map<String, Object> createGraph(Object name);

    /** 自动保存：一次把 nodes / edges / viewport 全量提交。 */
    Map<String, Object> updateGraph(String canvasId, Map<String, Object> body);

    /** 删掉整张图。 */
    void deleteGraph(String canvasId);

    /** 编导聊天记录：一张画布下可以有多段对话，业务是「upsert 某一段」。 */
    Map<String, Object> saveChat(String canvasId, Map<String, Object> body);

    /** 删掉一段历史对话（画布上的节点不动）。 */
    Map<String, Object> deleteChat(String canvasId, String chatId);

    /** 导出用的一份干净 JSON（不带 id，换台机器导入也不会撞号）。 */
    Map<String, Object> exportPayload(String canvasId);

    /** 导出文件名：把标题里不能进文件名的字符换成下划线。 */
    String exportFileName(String canvasId);

    /** 导入：整份 JSON 变成一张新画布，不覆盖现有的。 */
    Map<String, Object> importGraph(Object raw);

    /**
     * 全部节点画布的原始行（**数组**），供 {@code GET /api/state} 的 {@code nodeCanvases} 用。
     *
     * <p>对应 Python {@code node_canvas_snapshot()}：原版直接下发 {@code store.node_canvases}，
     * 这里按 {@code createdAt} 倒序返回一份浅拷贝，调用方改不动存档里的行。
     */
    List<Map<String, Object>> snapshots();
}

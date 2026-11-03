package com.lumenforge.service.ncanvas;

import java.util.List;
import java.util.Map;

/**
 * 本地素材库业务能力。
 *
 * <p>对应 Python 版 {@code app/node_canvas.py} 的 {@code register_upload / register_existing /
 * asset_to_image_ref / asset_lists / patch_asset / trash_asset / restore_asset / purge_asset /
 * clear_trash}。删除是「先进回收站」，永久删除才摘行并尝试删盘。
 */
public interface NodeAssetService {

    /** 上传素材的体积上限，跟原版 {@code express.raw({limit:'500mb'})} 对齐。 */
    long MAX_ASSET_BYTES = 500L * 1024L * 1024L;

    /** 取一条素材记录，不存在抛 {@link com.lumenforge.entity.ncanvas.NodeCanvasNotFoundException}。 */
    Map<String, Object> requireAsset(String assetId);

    /** 上传素材：走原始二进制（不引入 multipart 依赖），落 {@code uploads/} 下。 */
    Map<String, Object> registerUpload(byte[] buffer, String originalName, String mimeType);

    /** 把已经在磁盘上的文件登记进素材库（不复制）。 */
    Map<String, Object> registerExisting(Map<String, Object> body);

    /** 素材库里的图直接当人物参考图（同一份素材反复用只登记一条）。 */
    Map<String, Object> toImageRef(String assetId);

    /** 素材库两栏：{@code items}（在用）与 {@code trash}（回收站）。 */
    Map<String, Object> assetLists();

    /** 改收藏 / 改备注名。 */
    Map<String, Object> patchAsset(String assetId, Map<String, Object> body);

    /** 删除 = 先进回收站，文件不动。 */
    Map<String, Object> trashAsset(String assetId);

    /** 从回收站恢复。 */
    Map<String, Object> restoreAsset(String assetId);

    /** 永久删除：先摘记录，再在没人引用时删盘。 */
    Map<String, Object> purgeAsset(String assetId);

    /** 清空回收站，返回清掉几条、实际删掉几个文件。 */
    Map<String, Object> clearTrash();

    /**
     * 全部素材的原始行（**数组**，含已进回收站的），供 {@code GET /api/state} 的
     * {@code nodeAssets} 用。
     *
     * <p>对应 Python {@code node_asset_snapshot()}：原版 {@code [dict(a) for a in store.node_assets]}
     * 一条不落地下发，前端自己按 {@code deletedAt} 分两栏 —— 所以不能复用
     * {@link #assetLists()}（那个只给「在用 / 回收站」两个子数组）。
     */
    List<Map<String, Object>> snapshots();
}

package com.lumenforge.service.topaz;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Topaz 视频增强的业务入口。
 *
 * <p>本期迁移范围与 Python 版一致：只做「引擎探测 / 档位配置 / 本地素材清单 / 候选清单」，
 * 真正的增强流水线（tvai_fi + tvai_up + hevc_nvenc）明确未实现，四条执行类端点如实返回
 * 未实现说明，而不是假装排队。
 */
public interface TopazService {

    /** GET /api/topaz/candidates：把任务产物、视频产物和本地文件夹合成一张待增强清单。 */
    Map<String, Object> candidates();

    /** POST /api/topaz/enhance：本期未实现，抛 IllegalArgumentException 说明原因。 */
    Map<String, Object> enhance(Map<String, Object> body);

    /** POST /api/topaz/enhance-batch：本期未实现；空勾选先报「没有勾选任何视频」。 */
    Map<String, Object> enhanceBatch(Map<String, Object> body);

    /** POST /api/topaz/cancel：本期未实现。 */
    Map<String, Object> cancel(Map<String, Object> body);

    /** POST /api/topaz/restore：本期未实现。 */
    Map<String, Object> restore(Map<String, Object> body);

    /** POST /api/topaz/clear-failed：只清 failed / canceled 两种终态，返回清掉的条数。 */
    Map<String, Object> clearFailed();

    /** POST /api/topaz/config：设置 Topaz 安装目录，传空恢复自动探测。 */
    Map<String, Object> config(Map<String, Object> body);

    /** POST /api/topaz/look：切换质感档（出片滤镜）。 */
    Map<String, Object> look(Map<String, Object> body);

    /** POST /api/topaz/local：不带 dir 时只扫描当前目录；带 dir 则先存目录再扫描。 */
    Map<String, Object> local(Map<String, Object> body);

    /**
     * GET /api/topaz/local-file 的路径校验：必须是绝对路径、视频扩展名、真实文件，
     * 且落在允许的根目录之内。不合法返回 {@code null}（控制器据此回 404 空体）。
     */
    Path resolveLocalVideo(String rawPath);

    /** 本地视频的 Content-Type，按扩展名映射；未知扩展名回 application/octet-stream。 */
    String videoMime(String rawPath);

    /** 允许预览的根目录（本地素材目录 / outputs / topaz / videos / 数据根）。 */
    List<Path> allowedRoots();

    /**
     * 等价于 Python 的 {@code topaz_snapshot()}：给 {@code GET /api/state} 的 {@code topaz} 键用。
     *
     * <p>返回 12 个键：{@code found / bundled / ffmpeg / dir / modelsReady / tiers / look / looks /
     * localDir / localLimit / jobs}，键名与顺序和 Python 逐字符一致。
     */
    Map<String, Object> snapshot();
}

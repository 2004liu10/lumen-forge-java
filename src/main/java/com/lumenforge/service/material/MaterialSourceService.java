package com.lumenforge.service.material;

import com.lumenforge.entity.material.MaterialSource;

import java.util.List;
import java.util.Map;

/**
 * 素材分类（接口管理）业务。
 *
 * <p>对应 Python 版 {@code app/materials.py} 里与「分类」相关的那一半：
 * 分类的增删改、全局线路池、对外快照（Key 只回显掩码）、请求头拼装、线路拼装，
 * 以及 {@code fetch_material_items()} 的抓取入口。
 */
public interface MaterialSourceService {

    /**
     * 全部分类的对外快照，顺序按创建时间正序。
     *
     * <p>给 {@code GET /api/state} 复用：等价 Python
     * {@code [material_source_snapshot(s) for s in store.material_sources]}。
     */
    List<Map<String, Object>> listSnapshots();

    /**
     * 全局线路池（基地址列表），已归一化。
     *
     * <p>给 {@code GET /api/state} 复用：等价 Python {@code normalize_line_pool(store.material_line_pool)}，
     * 这里包成 {@code {"lines": [...]}} 以便直接铺进 state。
     */
    Map<String, Object> linePool();

    /**
     * 覆盖全局线路池；逐条校验必须是 http(s) 域名根。
     *
     * @return {@code {"lines": [...]}}
     */
    Map<String, Object> updateLinePool(Map<String, Object> body);

    /** 新建分类；{@code name} 为空抛「请填写分类名称」。 */
    Map<String, Object> create(Map<String, Object> body);

    /** 改分类；找不到抛「素材分类不存在」。 */
    Map<String, Object> update(String id, Map<String, Object> body);

    /** 删分类；找不到抛「素材分类不存在」。 */
    void delete(String id);

    /** 取分类实体；找不到抛「素材分类不存在」。 */
    MaterialSource require(String id);

    /** 分类的对外快照（Key 掩码）。 */
    Map<String, Object> snapshot(MaterialSource source);

    /** 拉这个分类的素材列表（{@code refresh=true} 跳过缓存）。 */
    Map<String, Object> items(String id, boolean refresh);

    /** 抓取时用的请求头：UA + 自定义 headers + 可选 API Key。 */
    Map<String, String> headers(MaterialSource source);

    /** 分类可用的全部线路（m3u8 模式会拼上全局线路池）。 */
    List<String> materialLines(MaterialSource source);
}

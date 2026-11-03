package com.lumenforge.service.material;

import com.lumenforge.entity.material.MaterialSource;

import java.util.List;
import java.util.Map;

/**
 * 素材抓取器（可插拔）。
 *
 * <p>Python 版对每种 mode 都写了真实的抓取实现（api 拉 JSON、redirect 跟跳转、
 * m3u8 拼线路并合并分片）。Java 版暂不联网抓取，只留这一个扩展点：
 * 以后要接某个站点，实现本接口并注册成 Spring Bean 即可，
 * 分类的增删改查、缓存、线路池都不需要改动。
 *
 * <p>返回值约定（与 {@code fetch_material_items()} 对齐）：
 * <ul>
 *   <li>{@code items}：{@code List<Map<String,Object>>}，每项含
 *       {@code id/url/poster/title/ext/hls}；</li>
 *   <li>{@code lines}：实际用上的线路，第一条会回显给前端；</li>
 *   <li>{@code message}：可选提示，例如「该素材源暂未接入抓取，请手动导入」。</li>
 * </ul>
 * 抓不到时抛 {@link IllegalArgumentException}，由 {@code GlobalExceptionHandler} 翻成 400。
 */
public interface MaterialFetcher {

    /**
     * 抓一批素材。
     *
     * @param source  分类（含 config_json 里的 mode / count / itemsPath / urlField 等）
     * @param headers 已拼好的请求头（含 User-Agent 与分类自己的 apiKey）
     * @param lines   已经算好的线路列表（m3u8 分类会把线路池拼进来）
     */
    Map<String, Object> fetch(MaterialSource source, Map<String, String> headers, List<String> lines);
}

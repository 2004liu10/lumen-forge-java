package com.lumenforge.service.topic;

import java.util.Map;

/**
 * 话题库用例。
 *
 * <p>对应 Python 版 {@code app/topics.py} 与 {@code app/api/topics_api.py}：话题按
 * 「去前导 # → 去所有空白 → 截 30 字」归一化去重，不绑视频，只记来源与使用次数。
 */
public interface TopicService {

    /** 话题列表 + 统计，{@code q} 按归一化后的子串匹配名字。 */
    Map<String, Object> list(String q);

    /** 收一批话题进库，返回 {@code topics/stats/added/existed/items}。 */
    Map<String, Object> add(Map<String, Object> body);

    /** 记一次「用过」，返回 {@code topics/stats/touched}。 */
    Map<String, Object> markUsed(Map<String, Object> body);

    /** 按 id 删一条，返回 {@code topics/stats/ok}；不存在时抛 {@code IllegalArgumentException}。 */
    Map<String, Object> delete(String id);

    /** 清空话题库，返回 {@code ok/removed}。 */
    Map<String, Object> clear();
}

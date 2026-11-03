package com.lumenforge.service.publish;

import java.util.Map;

/**
 * 发布记录用例。
 *
 * <p>对应 Python 版 {@code app/publish_log.py} 与 {@code app/api/publish_api.py}：
 * 记录「哪个视频文件、什么时候填过标题话题、什么时候真的发出去了」。
 *
 * <p>身份判定用 {@code path + size + mtimeMs} 三者一起 —— 同一个路径的视频被重新导出过
 * （剪辑软件再出一版）时 size 与 mtime 会变，那就该当成一条新作品，不能因为「路径见过」
 * 就拦着不让发。
 */
public interface PublishService {

    /** 全部发布记录，新的排前面，每行附 {@code fileExists}。 */
    Map<String, Object> listRecords();

    /** 程序里自己生成的、可以直接拿去发布的视频成品。 */
    Map<String, Object> listWorks();

    /** 查一条视频能不能再发，返回 {@code {blocked, warn, record, reason}}。 */
    Map<String, Object> check(Map<String, Object> body);

    /** 登记一次「已填表」，返回 {@code {ok, record, records}}。 */
    Map<String, Object> markFilled(Map<String, Object> body);

    /** 确认发布完成，返回 {@code {ok, records}}。 */
    Map<String, Object> markPublished(String id);

    /** 按文件身份直接标记发布，返回 {@code {ok, record, records}}。 */
    Map<String, Object> markPublishedByPath(Map<String, Object> body);

    /** 撤销「已发布」，返回 {@code {ok, records}}。 */
    Map<String, Object> unpublish(String id);

    /** 删除一条记录，返回 {@code {ok, records}}。 */
    Map<String, Object> removeRecord(String id);

    /** 清空全部记录，返回 {@code {ok, removed, records}}。 */
    Map<String, Object> clearRecords();

    /** 分析「发布过的视频」里哪些能删、哪些不能删。 */
    Map<String, Object> analyzeDeletable(Map<String, Object> body);

    /** 记下「这些视频的本地文件已经删了」，返回 {@code {ok, marked, records}}。 */
    Map<String, Object> markDeleted(Map<String, Object> body);
}

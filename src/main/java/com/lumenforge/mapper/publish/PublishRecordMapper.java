package com.lumenforge.mapper.publish;

import com.lumenforge.entity.publish.PublishRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 发布记录持久化入口。
 *
 * <p>排序键是 {@code publishedAt or filledAt or createdAt}（三者都可能是 0），
 * 无法用派生查询表达，因此统一在 service 里按 metadata 中的毫秒原值排序。
 */
public interface PublishRecordMapper extends JpaRepository<PublishRecord, String> {
}

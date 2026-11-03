package com.lumenforge.mapper.topaz;

import com.lumenforge.entity.topaz.TopazJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Topaz 增强任务数据访问层。
 *
 * <p>{@code findTop30ByOrderByCreatedAtDesc} 对应 Python 的 {@code topaz_jobs_snapshot()}
 * 只回前 30 条；{@code findByStatusInOrderByCreatedAtAsc} 供「清空失败记录」筛出 failed/canceled。
 */
public interface TopazJobMapper extends JpaRepository<TopazJob, String> {
    List<TopazJob> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

    List<TopazJob> findTop30ByOrderByCreatedAtDesc();
}

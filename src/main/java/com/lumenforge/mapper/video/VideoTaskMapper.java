package com.lumenforge.mapper.video;

import com.lumenforge.entity.video.VideoTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 视频任务仓储；队列按状态 + 创建时间取，列表按创建时间倒序取最近 400 条。 */
public interface VideoTaskMapper extends JpaRepository<VideoTask, String> {
    List<VideoTask> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
    List<VideoTask> findByStatusOrderByCreatedAtAsc(String status);
    List<VideoTask> findTop400ByOrderByCreatedAtDesc();
}

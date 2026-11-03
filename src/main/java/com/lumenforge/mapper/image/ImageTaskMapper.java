package com.lumenforge.mapper.image;

import com.lumenforge.entity.image.ImageTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 图片任务持久化入口；worker 只领取最早的一条排队任务，保证生图请求有序。 */
public interface ImageTaskMapper extends JpaRepository<ImageTask, String> {
    List<ImageTask> findByStatusOrderByCreatedAtAsc(String status);
    List<ImageTask> findTop400ByOrderByCreatedAtDesc();
}

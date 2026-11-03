package com.lumenforge.mapper.video;

import com.lumenforge.entity.video.VideoModel;
import org.springframework.data.jpa.repository.JpaRepository;

/** 视频模型配置仓储。 */
public interface VideoModelMapper extends JpaRepository<VideoModel, String> {
}

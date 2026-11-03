package com.lumenforge.mapper.video;

import com.lumenforge.entity.video.VideoInput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 视频输入素材仓储。 */
public interface VideoInputMapper extends JpaRepository<VideoInput, String> {
    List<VideoInput> findBySourceTaskId(String sourceTaskId);
}

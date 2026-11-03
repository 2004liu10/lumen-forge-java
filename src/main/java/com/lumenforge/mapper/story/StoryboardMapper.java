package com.lumenforge.mapper.story;

import com.lumenforge.entity.story.Storyboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 分镜剧本仓储；剧本按创建时间倒序展示（Python 版 `all_storyboard_snapshots` 同口径）。 */
public interface StoryboardMapper extends JpaRepository<Storyboard, String> {

    List<Storyboard> findAllByOrderByCreatedAtDesc();
}

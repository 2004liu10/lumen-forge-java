package com.lumenforge.mapper.topic;

import com.lumenforge.entity.topic.TopicHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 话题库持久化入口。
 *
 * <p>Python 版按 name 精确查第一条（{@code store.find}），这里用「按创建时间升序取首条」
 * 保证同名的重复记录（历史数据可能留下）命中顺序与 Python 的插入顺序一致。
 */
public interface TopicHistoryMapper extends JpaRepository<TopicHistory, String> {

    List<TopicHistory> findByTopicOrderByCreatedAtAsc(String topic);

    List<TopicHistory> findAllByOrderByCreatedAtDesc();
}

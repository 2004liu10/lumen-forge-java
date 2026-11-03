package com.lumenforge.mapper.task;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 生成任务数据访问层：统一任务持久化入口，状态编排和重试策略放在 Service 中维护。
 */
public interface GenerationTaskMapper extends JpaRepository<com.lumenforge.entity.task.GenerationTask, String> {}





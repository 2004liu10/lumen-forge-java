package com.lumenforge.mapper.web;

import com.lumenforge.entity.web.WebProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 网页平台配置数据访问层。slug 上有唯一索引，所以按 slug 查是稳定入口。
 */
public interface WebProviderMapper extends JpaRepository<WebProvider, String> {
    Optional<WebProvider> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<WebProvider> findAllByOrderByCreatedAtAsc();
}

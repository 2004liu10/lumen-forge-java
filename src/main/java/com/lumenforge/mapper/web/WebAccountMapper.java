package com.lumenforge.mapper.web;

import com.lumenforge.entity.web.WebAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 网页平台账号数据访问层。账号池按 provider_slug 分组，配合 (provider_slug, enabled) 索引。
 */
public interface WebAccountMapper extends JpaRepository<WebAccount, String> {
    List<WebAccount> findByProviderSlug(String providerSlug);

    List<WebAccount> findAllByOrderByCreatedAtAsc();
}

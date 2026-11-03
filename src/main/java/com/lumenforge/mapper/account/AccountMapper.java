package com.lumenforge.mapper.account;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号数据访问层：集中声明账号表的持久化入口。
 * 当前项目沿用 Spring Data JPA 自动实现 CRUD，避免手写重复 SQL；业务规则仍由 Service 负责。
 */
public interface AccountMapper extends JpaRepository<com.lumenforge.entity.account.Account, String> {}





package com.lumenforge.mapper.canvas;

import org.springframework.data.jpa.repository.JpaRepository;
import com.lumenforge.entity.canvas.Canvas;

/**
 * 画布数据访问层：Spring Data JPA 根据方法名生成按账号查询的 SQL，
 * 让业务层不需要关心具体查询语句和 EntityManager 操作。
 */
public interface CanvasMapper extends JpaRepository<com.lumenforge.entity.canvas.Canvas, String> {
    java.util.List<Canvas> findByAccountId(String accountId);
}





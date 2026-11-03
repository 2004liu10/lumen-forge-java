package com.lumenforge.mapper.material;

import com.lumenforge.entity.material.MaterialSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 素材分类仓储。 */
public interface MaterialSourceMapper extends JpaRepository<MaterialSource, String> {

    /** 按创建时间正序列出，保证界面顺序与 Python 的 append 顺序一致。 */
    List<MaterialSource> findAllByOrderByCreatedAtAsc();
}

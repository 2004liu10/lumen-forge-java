package com.lumenforge.mapper.material;

import com.lumenforge.entity.material.MaterialItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 素材条目缓存仓储。 */
public interface MaterialItemMapper extends JpaRepository<MaterialItem, String> {

    /** 某个分类下的全部缓存条目，按抓取时间倒序。 */
    List<MaterialItem> findBySourceIdOrderByFetchedAtDesc(String sourceId);

    /** 删掉某个分类的全部缓存（对应 Python 的 invalidate_material_items）。 */
    void deleteBySourceId(String sourceId);
}

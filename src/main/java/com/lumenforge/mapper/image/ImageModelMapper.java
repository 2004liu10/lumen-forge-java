package com.lumenforge.mapper.image;

import com.lumenforge.entity.image.ImageModel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 图片模型数据访问；启用状态过滤用于防止停用模型被新任务引用。 */
public interface ImageModelMapper extends JpaRepository<ImageModel, String> {
    List<ImageModel> findByEnabledTrueOrderByCreatedAtDesc();
}

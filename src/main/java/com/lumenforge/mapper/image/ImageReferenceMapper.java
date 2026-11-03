package com.lumenforge.mapper.image;

import com.lumenforge.entity.image.ImageReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 参考图库持久化。图库只保留最近的若干条，超出部分在业务层截断。 */
public interface ImageReferenceMapper extends JpaRepository<ImageReference, String> {
    List<ImageReference> findAllByOrderByCreatedAtDesc();
}

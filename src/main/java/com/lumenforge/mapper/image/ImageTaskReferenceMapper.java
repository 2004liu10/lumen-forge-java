package com.lumenforge.mapper.image;

import com.lumenforge.entity.image.ImageTaskReference;
import com.lumenforge.entity.image.ImageTaskReferenceKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 图片任务与参考图的关联表。
 *
 * <p>参考图本身还会被快照进 {@code image_tasks.result_json}，所以这里的行只是
 * 「这条任务用过这张图」的弱关联；任务或参考图被删时把行删掉即可，历史记录不会失真。
 */
public interface ImageTaskReferenceMapper extends JpaRepository<ImageTaskReference, ImageTaskReferenceKey> {
    List<ImageTaskReference> findByImageTaskIdOrderByPositionIndexAsc(String imageTaskId);

    void deleteByImageTaskId(String imageTaskId);

    void deleteByReferenceId(String referenceId);
}

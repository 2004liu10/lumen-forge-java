package com.lumenforge.entity.image;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * 图片任务与参考图的多对多关联。复合主键 (image_task_id, reference_id)，
 * positionIndex 保留提交顺序，保证上游拿到的参考图顺序与用户选择一致。
 */
@Entity
@Table(name = "image_task_references")
@IdClass(ImageTaskReferenceKey.class)
public class ImageTaskReference {
    @Id
    @Column(name = "image_task_id", length = 255, nullable = false)
    private String imageTaskId;

    @Id
    @Column(name = "reference_id", length = 255, nullable = false)
    private String referenceId;

    @Column(name = "position_index", nullable = false)
    private int positionIndex;

    protected ImageTaskReference() {
    }

    public ImageTaskReference(String imageTaskId, String referenceId, int positionIndex) {
        this.imageTaskId = imageTaskId;
        this.referenceId = referenceId;
        this.positionIndex = positionIndex;
    }

    public String getImageTaskId() {
        return imageTaskId;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public int getPositionIndex() {
        return positionIndex;
    }
}

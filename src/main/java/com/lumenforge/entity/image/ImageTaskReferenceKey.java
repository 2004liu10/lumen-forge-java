package com.lumenforge.entity.image;

import java.io.Serializable;
import java.util.Objects;

/** image_task_references 的复合主键。 */
public class ImageTaskReferenceKey implements Serializable {
    private static final long serialVersionUID = 1L;

    private String imageTaskId;
    private String referenceId;

    public ImageTaskReferenceKey() {
    }

    public ImageTaskReferenceKey(String imageTaskId, String referenceId) {
        this.imageTaskId = imageTaskId;
        this.referenceId = referenceId;
    }

    public String getImageTaskId() {
        return imageTaskId;
    }

    public String getReferenceId() {
        return referenceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ImageTaskReferenceKey)) return false;
        ImageTaskReferenceKey that = (ImageTaskReferenceKey) other;
        return Objects.equals(imageTaskId, that.imageTaskId) && Objects.equals(referenceId, that.referenceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(imageTaskId, referenceId);
    }
}

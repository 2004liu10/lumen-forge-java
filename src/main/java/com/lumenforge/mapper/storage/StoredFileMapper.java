package com.lumenforge.mapper.storage;

import com.lumenforge.entity.storage.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** 本地媒体文件索引入口；按分类与相对路径查回已登记的文件。 */
public interface StoredFileMapper extends JpaRepository<StoredFile, String> {
    Optional<StoredFile> findByRelativePath(String relativePath);
    List<StoredFile> findByCategoryOrderByCreatedAtDesc(String category);
}

package com.cloudmedia.api.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cloudmedia.api.entity.MediaFile;

import java.util.List;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, String> {
    // Lấy danh sách file trong một thư mục cụ thể của người dùng
    List<MediaFile> findByUserIdAndFolderId(String userId, String folderId);
    
    // Lấy danh sách file nằm ở thư mục gốc
    List<MediaFile> findByUserIdAndFolderIdIsNull(String userId);
}
package com.cloudmedia.api.repository;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cloudmedia.api.entity.Folder;

@Repository
public interface FolderRepository extends JpaRepository<Folder, String> {
    // Lấy danh sách các thư mục con trực tiếp của một thư mục (của một user cụ thể)
    List<Folder> findByUserIdAndParentFolderId(String userId, String parentFolderId);
    List<Folder> findByParentFolderIdAndUserId(String parentFolderId, String userId);
    // Lấy các thư mục nằm ở ngoài cùng (gốc - không có parent)
    List<Folder> findByUserIdAndParentFolderIdIsNull(String userId);
}
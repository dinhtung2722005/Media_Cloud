package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.Folder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FolderRepository
    extends JpaRepository<Folder, String>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Folder> {
  // Lấy danh sách các thư mục con trực tiếp của một thư mục (của một user cụ thể)
  List<Folder> findByUserIdAndParentFolderId(String userId, String parentFolderId);

  List<Folder> findByParentFolderIdAndUserId(String parentFolderId, String userId);

  // Lấy các thư mục nằm ở ngoài cùng (gốc - không có parent)
  List<Folder> findByUserIdAndParentFolderIdIsNull(String userId);
}

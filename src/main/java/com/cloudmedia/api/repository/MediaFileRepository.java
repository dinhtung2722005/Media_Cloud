package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.MediaFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaFileRepository
    extends JpaRepository<MediaFile, String>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<MediaFile> {
  boolean existsByStoragePathStartingWith(String prefix);

  @org.springframework.data.jpa.repository.Query(
      "select lower(f.extension),count(f),coalesce(sum(f.fileSize),0) from MediaFile f where"
          + " f.userId=:user and ((:folder is null and f.folderId is null) or f.folderId=:folder)"
          + " and f.status='Ready' group by lower(f.extension)")
  List<Object[]> overview(
      @org.springframework.data.repository.query.Param("user") String user,
      @org.springframework.data.repository.query.Param("folder") String folder);

  @org.springframework.data.jpa.repository.Query(
      "select coalesce(sum(f.fileSize),0) from MediaFile f where f.userId=:user")
  long usedBytes(@org.springframework.data.repository.query.Param("user") String user);

  boolean existsByStoragePath(String path);

  java.util.List<MediaFile> findByStatus(
      String status, org.springframework.data.domain.Pageable page);

  // Lấy danh sách file trong một thư mục cụ thể của người dùng
  List<MediaFile> findByUserIdAndFolderId(String userId, String folderId);

  List<MediaFile> findByFolderIdAndUserId(String folderId, String userId);

  // Lấy danh sách file nằm ở thư mục gốc
  List<MediaFile> findByUserIdAndFolderIdIsNull(String userId);
}

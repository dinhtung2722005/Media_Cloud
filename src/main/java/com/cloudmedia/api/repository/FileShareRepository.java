package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.FileShare;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FileShareRepository extends JpaRepository<FileShare, String> {
  Optional<FileShare> findByTokenHash(String tokenHash);

  List<FileShare> findByUserIdAndFileIdOrderByCreatedAtDescIdDesc(
      String userId, String fileId, Pageable page);

  @Query(
      "select s from FileShare s where s.userId=:user and s.fileId=:file order by case when"
          + " s.revokedAt is null and s.expiresAt>:now then 0 else 1 end,s.createdAt desc,s.id"
          + " desc")
  List<FileShare> listForOwner(
      @Param("user") String user,
      @Param("file") String file,
      @Param("now") Instant now,
      Pageable page);

  long countByUserIdAndFileIdAndRevokedAtIsNullAndExpiresAtAfter(
      String userId, String fileId, Instant now);

  long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(String userId, Instant now);

  @Modifying
  @Transactional
  @Query("delete from FileShare s where s.fileId=:file")
  void deleteForFile(@Param("file") String fileId);
}

package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.UploadSession;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {
  @Query(
      "select coalesce(sum(u.reservedBytes),0) from UploadSession u where u.userId=:user and"
          + " u.status in :states and u.expiresAt>:now")
  long reserved(
      @Param("user") String user,
      @Param("states") Collection<UploadSession.Status> states,
      @Param("now") Instant now);

  long countByUserIdAndStatusInAndExpiresAtAfter(
      String user, Collection<UploadSession.Status> states, Instant now);

  boolean existsByUserIdAndFolderIdAndStatusInAndExpiresAtAfter(
      String user, String folder, Collection<UploadSession.Status> states, Instant now);

  @Query("select u from UploadSession u where u.status in :states and u.expiresAt<=:now")
  List<UploadSession> expired(
      @Param("states") Collection<UploadSession.Status> states,
      @Param("now") Instant now,
      Pageable page);

  boolean existsByLeaseTokenAndLeaseUntilAfter(String token, Instant now);

  @Query(
      "select u from UploadSession u where u.cleaned=false and (u.status in :terminal or"
          + " u.expiresAt<=:now) order by u.expiresAt")
  List<UploadSession> cleanupCandidates(
      @Param("terminal") Collection<UploadSession.Status> terminal,
      @Param("now") Instant now,
      Pageable page);
}

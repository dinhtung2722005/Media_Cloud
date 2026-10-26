package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.MediaJob;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MediaJobRepository extends JpaRepository<MediaJob, String> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from MediaJob j where j.id=:id")
  Optional<MediaJob> lockById(@Param("id") String id);

  Optional<MediaJob> findByUserIdAndRequestKey(String userId, String key);

  long countByUserIdAndStatusIn(String user, Collection<MediaJob.Status> states);

  long countByUserIdAndStatusInAndLeaseUntilAfter(
      String user, Collection<MediaJob.Status> states, Instant now);

  boolean existsByUserIdAndFolderIdAndStatusIn(
      String user, String folder, Collection<MediaJob.Status> states);

  boolean existsByUserIdAndFileIdAndStatusIn(
      String user, String file, Collection<MediaJob.Status> states);

  boolean existsByLeaseTokenAndLeaseUntilAfter(String token, Instant now);

  boolean existsByIdAndStatus(String id, MediaJob.Status status);

  @Query(
      "select coalesce(sum(j.reservedBytes),0) from MediaJob j where j.userId=:user and j.status in"
          + " :states")
  long reserved(@Param("user") String user, @Param("states") Collection<MediaJob.Status> states);

  Page<MediaJob> findByUserIdAndStatusInOrderByCreatedAtDesc(
      String user, Collection<MediaJob.Status> states, Pageable page);

  Page<MediaJob> findByUserIdOrderByCreatedAtDesc(String user, Pageable page);

  @Query("select j from MediaJob j where j.status in :states and j.leaseUntil<=:now")
  List<MediaJob> expired(
      @Param("states") Collection<MediaJob.Status> states,
      @Param("now") Instant now,
      Pageable page);

  @Query(
      "select j from MediaJob j where j.status in :states and not exists (select o from JobOutbox o"
          + " where o.id=j.id)")
  List<MediaJob> withoutOutbox(@Param("states") Collection<MediaJob.Status> states, Pageable page);

  @Query("select j from MediaJob j where j.status in :states and j.updatedAt<:cutoff")
  List<MediaJob> stalled(
      @Param("states") Collection<MediaJob.Status> states,
      @Param("cutoff") Instant cutoff,
      Pageable page);

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update MediaJob j set j.leaseUntil=:until where j.id=:id and j.leaseToken=:token and"
          + " j.status=:state and j.leaseUntil>:now")
  int heartbeatState(
      @Param("id") String id,
      @Param("token") String token,
      @Param("now") Instant now,
      @Param("until") Instant until,
      @Param("state") MediaJob.Status state);

  default int heartbeat(String id, String token, Instant now, Instant until) {
    return heartbeatState(id, token, now, until, MediaJob.Status.RUNNING);
  }

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update MediaJob j set j.progress=:progress,j.updatedAt=:now where j.id=:id and"
          + " j.leaseToken=:token and j.status=:state and j.leaseUntil>:now")
  int progressState(
      @Param("id") String id,
      @Param("token") String token,
      @Param("progress") int progress,
      @Param("now") Instant now,
      @Param("state") MediaJob.Status state);

  default int progress(String id, String token, int progress, Instant now) {
    return progressState(id, token, progress, now, MediaJob.Status.RUNNING);
  }

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update MediaJob j set j.status=:next,j.updatedAt=:now where j.id=:id and j.status in"
          + " :allowed")
  int transition(
      @Param("id") String id,
      @Param("next") MediaJob.Status next,
      @Param("allowed") Collection<MediaJob.Status> allowed,
      @Param("now") Instant now);
}

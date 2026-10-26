package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.JobOutbox;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface JobOutboxRepository extends JpaRepository<JobOutbox, String> {
  @Query(
      "select o.id from JobOutbox o where o.sent=false and o.nextAttemptAt<=:now and (o.leaseUntil"
          + " is null or o.leaseUntil<=:now) order by o.nextAttemptAt")
  List<String> due(@Param("now") Instant now, Pageable page);

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update JobOutbox o set o.leaseToken=:token,o.leaseUntil=:until,o.attempts=o.attempts+1 where"
          + " o.id=:id and o.sent=false and o.nextAttemptAt<=:now and (o.leaseUntil is null or"
          + " o.leaseUntil<=:now)")
  int claim(
      @Param("id") String id,
      @Param("token") String token,
      @Param("now") Instant now,
      @Param("until") Instant until);

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update JobOutbox o set o.sent=true,o.leaseToken=null,o.leaseUntil=null where o.id=:id and"
          + " o.leaseToken=:token")
  int sent(@Param("id") String id, @Param("token") String token);

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update JobOutbox o set o.leaseToken=null,o.leaseUntil=null,o.nextAttemptAt=:next where"
          + " o.id=:id and o.leaseToken=:token")
  int retry(@Param("id") String id, @Param("token") String token, @Param("next") Instant next);
}

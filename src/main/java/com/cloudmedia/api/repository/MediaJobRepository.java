package com.cloudmedia.api.repository;

import com.cloudmedia.api.entity.MediaJob;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MediaJobRepository extends JpaRepository<MediaJob, String> {
    boolean existsByIdAndStatus(String id, MediaJob.Status status);

    @Modifying(clearAutomatically = true) @Transactional
    @Query("update MediaJob j set j.status = :next, j.updatedAt = :now where j.id = :id and j.status in :allowed")
    int transition(@Param("id") String id, @Param("next") MediaJob.Status next,
                   @Param("allowed") java.util.Collection<MediaJob.Status> allowed, @Param("now") Instant now);
}

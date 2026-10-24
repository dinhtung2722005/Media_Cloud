package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.service.MediaJobService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media/jobs")
public class MediaJobController {
    private final MediaJobService jobs;
    public MediaJobController(MediaJobService jobs) { this.jobs = jobs; }

    @GetMapping("/{id}")
    public JobView status(@PathVariable String id) {
        MediaJob job = jobs.requireOwned(id, userId());
        return new JobView(job.getId(), job.getType(), job.getStatus(),
                job.getStatus() == MediaJob.Status.COMPLETED ? job.getId() : null, job.getCreatedAt(), job.getUpdatedAt());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        jobs.cancel(id, userId());
        return ResponseEntity.accepted().body(Map.of("jobId", id, "status", "CANCELLED"));
    }

    private String userId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public record JobView(String jobId, MediaJob.Type type, MediaJob.Status status,
                          String resultFileId, Instant createdAt, Instant updatedAt) {}
}

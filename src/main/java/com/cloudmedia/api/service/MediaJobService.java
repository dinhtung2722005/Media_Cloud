package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.messaging.*;
import com.cloudmedia.api.repository.MediaJobRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaJobService {
    private final MediaJobRepository jobs;
    private final MediaJobPublisher publisher;

    public MediaJobService(MediaJobRepository jobs, MediaJobPublisher publisher) {
        this.jobs = jobs;
        this.publisher = publisher;
    }

    public String enqueueConversion(String userId, String fileId) {
        return submit(new MediaJob(UUID.randomUUID().toString(), userId, MediaJob.Type.CONVERT_MP3, fileId, null, null));
    }

    public String enqueueYouTube(String userId, String url, String folderId) {
        return submit(new MediaJob(UUID.randomUUID().toString(), userId, MediaJob.Type.YOUTUBE, null, url, folderId));
    }

    private String submit(MediaJob job) {
        jobs.saveAndFlush(job);
        try {
            publisher.publish(new MediaJobMessage(1, job.getId()));
            return job.getId();
        } catch (JobSubmissionException exception) {
            jobs.transition(job.getId(), MediaJob.Status.PUBLISH_FAILED, List.of(MediaJob.Status.QUEUED), Instant.now());
            throw exception;
        }
    }

    public MediaJob find(String id) {
        return jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tác vụ"));
    }

    public MediaJob requireOwned(String id, String userId) {
        MediaJob job = find(id);
        if (!userId.equals(job.getUserId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tác vụ");
        return job;
    }

    public boolean isCancelled(String id) {
        return jobs.existsByIdAndStatus(id, MediaJob.Status.CANCELLED);
    }

    public boolean start(String id) {
        return jobs.transition(id, MediaJob.Status.RUNNING,
                List.of(MediaJob.Status.QUEUED, MediaJob.Status.RUNNING, MediaJob.Status.FAILED), Instant.now()) == 1;
    }

    public void completed(String id) { transitionActive(id, MediaJob.Status.COMPLETED); }
    public void failed(String id) { transitionActive(id, MediaJob.Status.FAILED); }
    public void requeue(String id) { transitionActive(id, MediaJob.Status.QUEUED); }

    private void transitionActive(String id, MediaJob.Status next) {
        jobs.transition(id, next, List.of(MediaJob.Status.QUEUED, MediaJob.Status.RUNNING, MediaJob.Status.FAILED), Instant.now());
    }

    public void cancel(String id, String userId) {
        MediaJob job = requireOwned(id, userId);
        if (job.getStatus() == MediaJob.Status.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tác vụ đã hoàn tất");
        }
        int changed = jobs.transition(id, MediaJob.Status.CANCELLED, List.of(MediaJob.Status.QUEUED, MediaJob.Status.RUNNING,
                MediaJob.Status.FAILED, MediaJob.Status.PUBLISH_FAILED), Instant.now());
        if (changed == 0 && requireOwned(id, userId).getStatus() != MediaJob.Status.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tác vụ đã hoàn tất");
        }
    }
}

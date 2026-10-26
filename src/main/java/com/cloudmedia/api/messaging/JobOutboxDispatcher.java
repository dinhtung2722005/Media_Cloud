package com.cloudmedia.api.messaging;

import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.repository.*;
import com.cloudmedia.api.service.MediaJobService;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobOutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(JobOutboxDispatcher.class);
  private final JobOutboxRepository outbox;
  private final MediaJobRepository jobs;
  private final MediaJobPublisher publisher;
  private final MediaJobService service;

  public JobOutboxDispatcher(
      JobOutboxRepository outbox,
      MediaJobRepository jobs,
      MediaJobPublisher publisher,
      MediaJobService service) {
    this.outbox = outbox;
    this.jobs = jobs;
    this.publisher = publisher;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${app.jobs.outbox-poll-ms:1000}")
  public void dispatch() {
    for (String id : outbox.due(Instant.now(), PageRequest.of(0, 25))) {
      String token = UUID.randomUUID().toString();
      try {
        if (outbox.claim(id, token, Instant.now(), Instant.now().plusSeconds(30)) != 1) continue;
        MediaJob job = jobs.findById(id).orElse(null);
        if (job == null || job.terminal()) {
          outbox.sent(id, token);
          continue;
        }
        publisher.publish(new MediaJobMessage(1, id));
        outbox.sent(id, token);
        jobs.transition(
            id,
            MediaJob.Status.QUEUED,
            List.of(MediaJob.Status.PENDING_DISPATCH, MediaJob.Status.PUBLISH_FAILED),
            Instant.now());
      } catch (RuntimeException error) {
        try {
          outbox.retry(id, token, Instant.now().plusSeconds(5));
        } catch (RuntimeException ignored) {
        }
        log.warn("Outbox sẽ thử gửi lại job {} ({})", id, error.getClass().getSimpleName());
      }
    }
  }

  @Scheduled(fixedDelayString = "${app.jobs.recovery-poll-ms:10000}")
  public void recover() {
    List<MediaJob> expired =
        jobs.expired(
            List.of(MediaJob.Status.RUNNING, MediaJob.Status.CANCEL_REQUESTED),
            Instant.now(),
            PageRequest.of(0, 50));
    for (MediaJob job : expired)
      try {
        service.recover(job.getId());
      } catch (RuntimeException error) {
        log.warn("Chưa thể phục hồi job {}", job.getId());
      }
    for (MediaJob job :
        jobs.withoutOutbox(
            List.of(
                MediaJob.Status.QUEUED,
                MediaJob.Status.PENDING_DISPATCH,
                MediaJob.Status.PUBLISH_FAILED,
                MediaJob.Status.RETRYING),
            PageRequest.of(0, 50)))
      try {
        service.recover(job.getId());
      } catch (RuntimeException error) {
        log.warn("Chưa thể phục hồi outbox {}", job.getId());
      }
    for (MediaJob job :
        jobs.stalled(
            List.of(
                MediaJob.Status.QUEUED,
                MediaJob.Status.PENDING_DISPATCH,
                MediaJob.Status.PUBLISH_FAILED,
                MediaJob.Status.RETRYING),
            Instant.now().minusSeconds(300),
            PageRequest.of(0, 25)))
      try {
        service.recover(job.getId());
      } catch (RuntimeException error) {
        log.warn("Chưa thể kiểm tra delivery của job {}", job.getId());
      }
  }
}

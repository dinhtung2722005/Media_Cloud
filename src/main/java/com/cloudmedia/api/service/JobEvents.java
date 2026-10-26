package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaJob;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class JobEvents {
  public record Notice(String userId, Map<String, Object> payload) {}

  private final ApplicationEventPublisher events;

  public JobEvents(ApplicationEventPublisher events) {
    this.events = events;
  }

  public void publish(MediaJob job) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("taskId", job.getId());
    data.put(
        "taskName",
        job.getType() == MediaJob.Type.CONVERT_MP3 ? "Chuyển MP3" : "Tải video YouTube");
    data.put("status", job.getStatus().name());
    data.put("progress", job.getProgress());
    data.put("attempts", job.getAttempts());
    data.put("updatedAt", job.getUpdatedAt().toString());
    if (job.getErrorMessage() != null) data.put("message", job.getErrorMessage());
    Notice event = new Notice(job.getUserId(), Map.copyOf(data));
    AccountTransactions.afterCommit(
        () -> {
          try {
            events.publishEvent(event);
          } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(JobEvents.class)
                .warn("Thông báo tiến độ sẽ được khôi phục qua API");
          }
        });
  }
}

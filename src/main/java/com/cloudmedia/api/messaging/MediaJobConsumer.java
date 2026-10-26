package com.cloudmedia.api.messaging;

import com.cloudmedia.api.config.RabbitMqConfig;
import com.cloudmedia.api.service.*;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class MediaJobConsumer {
  private final MediaJobService jobs;
  private final MediaEngineService engine;

  public MediaJobConsumer(MediaJobService jobs, MediaEngineService engine) {
    this.jobs = jobs;
    this.engine = engine;
  }

  @RabbitListener(queues = RabbitMqConfig.QUEUE)
  public void consume(MediaJobMessage message) {
    try {
      if (message == null
          || message.version() != 1
          || !UUID.fromString(message.jobId()).toString().equals(message.jobId()))
        throw new IllegalArgumentException();
    } catch (RuntimeException error) {
      throw new AmqpRejectAndDontRequeueException("Thông điệp tác vụ không hợp lệ", error);
    }
    MediaJobService.Execution execution = jobs.claim(message.jobId()).orElse(null);
    if (execution == null) return;
    MediaEngineService.Prepared result = null;
    try {
      result =
          engine.prepare(
              execution.job(),
              execution.token(),
              jobs.cancellation(execution),
              progress -> jobs.progress(execution, progress));
      if (!jobs.finish(execution, result.file())) jobs.discard(execution, result);
    } catch (RuntimeException error) {
      if (result != null) jobs.discard(execution, result);
      jobs.failed(execution, error);
    }
  }
}

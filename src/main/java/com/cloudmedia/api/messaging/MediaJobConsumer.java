package com.cloudmedia.api.messaging;

import com.cloudmedia.api.config.RabbitMqConfig;
import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.service.*;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import org.springframework.amqp.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MediaJobConsumer {
    private final MediaJobService jobs;
    private final MediaEngineService engine;
    private final Object[] locks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public MediaJobConsumer(MediaJobService jobs, MediaEngineService engine) {
        this.jobs = jobs;
        this.engine = engine;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE)
    public void consume(MediaJobMessage message) {
        try {
            if (message == null || message.version() != 1 || message.jobId() == null
                    || !UUID.fromString(message.jobId()).toString().equals(message.jobId())) {
                throw new IllegalArgumentException("Invalid media job message");
            }
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("Thông điệp tác vụ không hợp lệ", exception);
        }
        synchronized (locks[Math.floorMod(message.jobId().hashCode(), locks.length)]) {
            MediaJob job;
            try { job = jobs.find(message.jobId()); }
            catch (ResponseStatusException exception) {
                throw new AmqpRejectAndDontRequeueException("Không tìm thấy dữ liệu tác vụ", exception);
            }
            if (!jobs.start(job.getId())) return; // Completed, cancelled or failed to publish.
            try {
                switch (job.getType()) {
                    case CONVERT_MP3 -> engine.convertToMp3(job.getFileId(), job.getUserId(), job.getId(),
                            () -> jobs.isCancelled(job.getId()));
                    case YOUTUBE -> engine.downloadYouTubeVideo(job.getUrl(), job.getUserId(), job.getFolderId(), job.getId(),
                            () -> jobs.isCancelled(job.getId()));
                }
                jobs.completed(job.getId());
            } catch (CancellationException exception) {
                if (!jobs.isCancelled(job.getId())) { jobs.failed(job.getId()); throw exception; }
                // Acknowledging a cancelled delivery removes it from the work queue.
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    jobs.requeue(job.getId());
                    throw new ImmediateRequeueAmqpException("Worker bị ngắt; trả tác vụ cho RabbitMQ", exception);
                }
                jobs.failed(job.getId());
                throw exception; // Container retries; exhausted failures go to the dead-letter queue.
            }
        }
    }
}

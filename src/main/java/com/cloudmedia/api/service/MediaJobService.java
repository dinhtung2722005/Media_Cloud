package com.cloudmedia.api.service;

import com.cloudmedia.api.cache.DriveCache;
import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
public class MediaJobService {
  public record Execution(MediaJob job, String token) {}

  private final MediaJobRepository jobs;
  private final JobOutboxRepository outbox;
  private final MediaFileRepository files;
  private final FolderRepository folders;
  private final AccountTransactions accounts;
  private final QuotaService quotas;
  private final DriveCache cache;
  private final JobEvents events;
  private final long maxOutput;
  private final int maxAttempts;
  private final int leaseSeconds;

  public MediaJobService(
      MediaJobRepository jobs,
      JobOutboxRepository outbox,
      MediaFileRepository files,
      FolderRepository folders,
      AccountTransactions accounts,
      QuotaService quotas,
      DriveCache cache,
      JobEvents events,
      @Value("${app.jobs.output-max-bytes:1073741824}") long maxOutput,
      @Value("${app.rabbit.retry-max-attempts:2}") int maxAttempts,
      @Value("${app.jobs.lease-seconds:45}") int leaseSeconds) {
    if (maxOutput < 1 || maxAttempts < 1 || maxAttempts > 10 || leaseSeconds < 10)
      throw new IllegalArgumentException("Job limits invalid");
    this.jobs = jobs;
    this.outbox = outbox;
    this.files = files;
    this.folders = folders;
    this.accounts = accounts;
    this.quotas = quotas;
    this.cache = cache;
    this.events = events;
    this.maxOutput = maxOutput;
    this.maxAttempts = maxAttempts;
    this.leaseSeconds = leaseSeconds;
  }

  public String enqueueConversion(String user, String file) {
    return enqueueConversion(user, file, null);
  }

  public String enqueueConversion(String user, String file, String key) {
    return submit(user, MediaJob.Type.CONVERT_MP3, file, null, null, key);
  }

  public String enqueueYouTube(String user, String url, String folder) {
    return enqueueYouTube(user, url, folder, null);
  }

  public String enqueueYouTube(String user, String url, String folder, String key) {
    return submit(user, MediaJob.Type.YOUTUBE, null, url, folder, key);
  }

  private String submit(
      String user, MediaJob.Type type, String file, String url, String folder, String suppliedKey) {
    String key = InputRules.key(suppliedKey),
        target = folder == null || folder.isBlank() ? null : folder;
    String hash = hash(type + "\n" + file + "\n" + url + "\n" + target);
    return accounts.locked(
        user,
        () -> {
          if (key != null) {
            Optional<MediaJob> old = jobs.findByUserIdAndRequestKey(user, key);
            if (old.isPresent()) {
              if (!hash.equals(old.get().getRequestHash()))
                throw DomainException.conflict(
                    "IDEMPOTENCY_CONFLICT", "Khóa yêu cầu đã được dùng với nội dung khác");
              return old.get().getId();
            }
          }
          String destination = target;
          if (type == MediaJob.Type.CONVERT_MP3) {
            MediaFile source =
                files
                    .findById(file)
                    .filter(f -> user.equals(f.getUserId()) && "Ready".equals(f.getStatus()))
                    .orElseThrow(DomainException::missing);
            destination = source.getFolderId();
          }
          requireFolder(destination, user);
          quotas.acceptJob(user);
          quotas.reserve(user, maxOutput);
          MediaJob job =
              new MediaJob(UUID.randomUUID().toString(), user, type, file, url, destination);
          job.submit(key, hash, maxOutput, maxAttempts);
          jobs.saveAndFlush(job);
          outbox.saveAndFlush(new JobOutbox(job.getId()));
          events.publish(job);
          return job.getId();
        });
  }

  public MediaJob find(String id) {
    return jobs.findById(id).orElseThrow(DomainException::missing);
  }

  public MediaJob requireOwned(String id, String user) {
    MediaJob job = find(id);
    if (!user.equals(job.getUserId())) throw DomainException.missing();
    return job;
  }

  public Page<MediaJob> list(String user, boolean active, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw DomainException.invalid("Phân trang không hợp lệ");
    return active
        ? jobs.findByUserIdAndStatusInOrderByCreatedAtDesc(
            user, MediaJob.ACTIVE, PageRequest.of(page, size))
        : jobs.findByUserIdOrderByCreatedAtDesc(user, PageRequest.of(page, size));
  }

  public Optional<Execution> claim(String id) {
    MediaJob snapshot = find(id);
    return accounts.locked(
        snapshot.getUserId(),
        () -> {
          MediaJob job = jobs.lockById(id).orElseThrow(DomainException::missing);
          Instant now = Instant.now();
          if (job.terminal() || job.getStatus() == MediaJob.Status.CANCEL_REQUESTED)
            return Optional.empty();
          if (job.getStatus() == MediaJob.Status.RUNNING
              && job.getLeaseUntil() != null
              && job.getLeaseUntil().isAfter(now)) return Optional.empty();
          if ((job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(now))
              || !quotas.canRun(job.getUserId())) {
            schedule(
                job,
                job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(now)
                    ? job.getNextAttemptAt()
                    : now.plusSeconds(2));
            return Optional.empty();
          }
          if (files
              .findById(job.getId())
              .filter(f -> job.getUserId().equals(f.getUserId()) && "Ready".equals(f.getStatus()))
              .isPresent()) {
            job.complete();
            stop(job);
            cache.invalidateAfterCommit(job.getUserId());
            events.publish(job);
            return Optional.empty();
          }
          if (job.getReservedBytes() == 0) {
            try {
              quotas.reserve(job.getUserId(), maxOutput);
              job.reserve(maxOutput);
            } catch (DomainException full) {
              job.failure(full.code(), full.getMessage(), null);
              stop(job);
              events.publish(job);
              return Optional.empty();
            }
          }
          if (job.getAttempts() >= Math.max(1, job.getMaxAttempts())) {
            job.failure("ATTEMPTS_EXHAUSTED", "Tác vụ đã hết số lần thử", null);
            stop(job);
            events.publish(job);
            return Optional.empty();
          }
          try {
            requireFolder(job.getFolderId(), job.getUserId());
          } catch (DomainException invalid) {
            job.failure("FOLDER_MISSING", "Thư mục đích không còn tồn tại", null);
            stop(job);
            events.publish(job);
            return Optional.empty();
          }
          String token = UUID.randomUUID().toString();
          job.claim(token, now.plusSeconds(leaseSeconds));
          jobs.saveAndFlush(job);
          events.publish(job);
          return Optional.of(new Execution(job, token));
        });
  }

  public BooleanSupplier cancellation(Execution execution) {
    java.util.concurrent.atomic.AtomicLong pulse = new java.util.concurrent.atomic.AtomicLong();
    return () -> {
      Instant now = Instant.now();
      if (now.toEpochMilli() >= pulse.get()) {
        if (jobs.heartbeat(
                execution.job().getId(), execution.token(), now, now.plusSeconds(leaseSeconds))
            != 1) return true;
        pulse.set(now.plusSeconds(Math.max(1, leaseSeconds / 3)).toEpochMilli());
      }
      MediaJob job = find(execution.job().getId());
      return job.getStatus() != MediaJob.Status.RUNNING
          || !execution.token().equals(job.getLeaseToken())
          || job.getLeaseUntil() == null
          || !job.getLeaseUntil().isAfter(now);
    };
  }

  public void progress(Execution execution, int percent) {
    int value = Math.max(0, Math.min(99, percent));
    if (jobs.progress(execution.job().getId(), execution.token(), value, Instant.now()) == 1)
      events.publish(find(execution.job().getId()));
  }

  public boolean finish(Execution execution, MediaFile result) {
    return accounts.locked(
        execution.job().getUserId(),
        () -> {
          MediaJob job =
              jobs.lockById(execution.job().getId()).orElseThrow(DomainException::missing);
          if (!owns(job, execution.token())) return false;
          if (job.getStatus() == MediaJob.Status.CANCEL_REQUESTED) {
            job.cancel();
            stop(job);
            events.publish(job);
            return false;
          }
          if (job.getStatus() != MediaJob.Status.RUNNING) return false;
          requireFolder(job.getFolderId(), job.getUserId());
          if (!job.getId().equals(result.getId())
              || !job.getUserId().equals(result.getUserId())
              || !Objects.equals(job.getFolderId(), result.getFolderId()))
            throw new IllegalArgumentException("Result does not belong to the execution");
          if (result.getFileSize() > maxOutput)
            throw DomainException.invalid("Kết quả vượt giới hạn dung lượng");
          quotas.finish(job.getUserId(), result.getFileSize(), job.getReservedBytes());
          files.saveAndFlush(result);
          job.complete();
          jobs.saveAndFlush(job);
          stop(job);
          cache.invalidateAfterCommit(job.getUserId());
          events.publish(job);
          return true;
        });
  }

  public void failed(Execution execution, Throwable cause) {
    accounts.locked(
        execution.job().getUserId(),
        () -> {
          MediaJob job =
              jobs.lockById(execution.job().getId()).orElseThrow(DomainException::missing);
          if (!owns(job, execution.token())) return;
          if (job.getStatus() == MediaJob.Status.CANCEL_REQUESTED) {
            job.cancel();
            stop(job);
          } else {
            boolean retry =
                job.getAttempts() < Math.max(1, job.getMaxAttempts())
                    && !(cause instanceof DomainException);
            Instant when =
                retry
                    ? Instant.now().plusSeconds(Math.min(60, 1L << Math.min(job.getAttempts(), 5)))
                    : null;
            job.failure(
                cause instanceof DomainException domain ? domain.code() : "MEDIA_PROCESS_FAILED",
                cause instanceof DomainException
                    ? cause.getMessage()
                    : "Không thể xử lý media. Vui lòng kiểm tra tệp hoặc đường dẫn.",
                when);
            if (retry) schedule(job, when);
            else stop(job);
          }
          jobs.saveAndFlush(job);
          events.publish(job);
        });
  }

  public void discard(Execution execution, MediaEngineService.Prepared output) {
    try {
      accounts.locked(
          execution.job().getUserId(),
          () -> {
            if (!files.existsByStoragePath(output.file().getStoragePath())) output.close();
          });
    } catch (RuntimeException unavailable) {
      org.slf4j.LoggerFactory.getLogger(MediaJobService.class)
          .warn("Giữ staging để đối soát khi chưa xác định được kết quả commit job");
    }
  }

  public void cancel(String id, String user) {
    requireOwned(id, user);
    accounts.locked(
        user,
        () -> {
          MediaJob job = jobs.lockById(id).orElseThrow(DomainException::missing);
          if (!user.equals(job.getUserId())) throw DomainException.missing();
          if (job.getStatus() == MediaJob.Status.COMPLETED)
            throw DomainException.conflict("JOB_COMPLETED", "Tác vụ đã hoàn tất");
          if (job.getStatus() == MediaJob.Status.CANCELLED
              || job.getStatus() == MediaJob.Status.CANCEL_REQUESTED) return;
          job.requestCancel();
          jobs.saveAndFlush(job);
          if (job.getStatus() == MediaJob.Status.CANCELLED) stop(job);
          events.publish(job);
        });
  }

  public void recover(String id) {
    MediaJob previous = find(id);
    accounts.locked(
        previous.getUserId(),
        () -> {
          MediaJob job = jobs.lockById(id).orElseThrow(DomainException::missing);
          if (job.terminal()) return;
          if (job.getStatus() == MediaJob.Status.RUNNING
              || job.getStatus() == MediaJob.Status.CANCEL_REQUESTED) {
            if (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(Instant.now())) return;
            if (job.getStatus() == MediaJob.Status.CANCEL_REQUESTED) {
              job.cancel();
              stop(job);
            } else {
              Instant when =
                  job.getAttempts() < Math.max(1, job.getMaxAttempts()) ? Instant.now() : null;
              job.failure("WORKER_LOST", "Lượt xử lý đã mất kết nối", when);
              if (when != null) schedule(job, when);
              else stop(job);
            }
            events.publish(job);
          } else {
            job.redispatch();
            schedule(job, job.getNextAttemptAt() == null ? Instant.now() : job.getNextAttemptAt());
          }
          jobs.saveAndFlush(job);
        });
  }

  private boolean owns(MediaJob job, String token) {
    return token.equals(job.getLeaseToken())
        && job.getLeaseUntil() != null
        && job.getLeaseUntil().isAfter(Instant.now());
  }

  private void schedule(MediaJob job, Instant when) {
    JobOutbox item = outbox.findById(job.getId()).orElseGet(() -> new JobOutbox(job.getId()));
    item.reschedule(when);
    outbox.saveAndFlush(item);
  }

  private void stop(MediaJob job) {
    outbox
        .findById(job.getId())
        .ifPresent(
            item -> {
              item.stop();
              outbox.save(item);
            });
  }

  private void requireFolder(String id, String user) {
    if (id != null)
      folders
          .findById(id)
          .filter(f -> user.equals(f.getUserId()))
          .orElseThrow(DomainException::missing);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

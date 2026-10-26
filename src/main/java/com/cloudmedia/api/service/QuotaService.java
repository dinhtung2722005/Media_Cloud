package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuotaService {
  public static final List<UploadSession.Status> UPLOAD_ACTIVE =
      List.of(UploadSession.Status.OPEN, UploadSession.Status.MERGING);
  private final MediaFileRepository files;
  private final MediaJobRepository jobs;
  private final UploadSessionRepository uploads;
  private final long maximum;
  private final int queued;
  private final int running;

  public QuotaService(
      MediaFileRepository files,
      MediaJobRepository jobs,
      UploadSessionRepository uploads,
      @Value("${app.quota.storage-bytes:21474836480}") long maximum,
      @Value("${app.quota.active-jobs:10}") int queued,
      @Value("${app.quota.running-jobs:2}") int running) {
    if (maximum < 1 || queued < 1 || running < 1 || running > queued)
      throw new IllegalArgumentException("Quota configuration invalid");
    this.files = files;
    this.jobs = jobs;
    this.uploads = uploads;
    this.maximum = maximum;
    this.queued = queued;
    this.running = running;
  }

  public void reserve(String user, long bytes) {
    if (bytes < 0 || bytes > maximum)
      throw new DomainException(
          HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA", "Vượt hạn mức lưu trữ");
    long used = files.usedBytes(user),
        held =
            jobs.reserved(user, MediaJob.ACTIVE)
                + uploads.reserved(user, UPLOAD_ACTIVE, Instant.now());
    if (used > maximum - held || bytes > maximum - held - used)
      throw new DomainException(
          HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA", "Không còn đủ dung lượng lưu trữ");
  }

  public void finish(String user, long actual, long ownReservation) {
    reserve(user, Math.max(0, actual - ownReservation));
  }

  public void acceptJob(String user) {
    if (jobs.countByUserIdAndStatusIn(user, MediaJob.ACTIVE) >= queued)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "JOB_QUOTA",
          "Đã đạt giới hạn tác vụ đang chờ hoặc đang chạy");
  }

  public boolean canRun(String user) {
    return jobs.countByUserIdAndStatusInAndLeaseUntilAfter(
            user, List.of(MediaJob.Status.RUNNING, MediaJob.Status.CANCEL_REQUESTED), Instant.now())
        < running;
  }
}

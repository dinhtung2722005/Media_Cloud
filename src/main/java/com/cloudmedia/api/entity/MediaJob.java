package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(
    name = "media_jobs",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_job_request",
            columnNames = {"user_id", "request_key"}),
    indexes = {
      @Index(name = "idx_job_owner_status", columnList = "user_id,status"),
      @Index(name = "idx_job_lease", columnList = "status,lease_until"),
      @Index(name = "idx_job_owner_folder", columnList = "user_id,folder_id")
    })
public class MediaJob {
  public enum Type {
    CONVERT_MP3,
    YOUTUBE
  }

  public enum Status {
    PENDING_DISPATCH,
    QUEUED,
    RUNNING,
    RETRYING,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    PUBLISH_FAILED
  }

  public static final Set<Status> ACTIVE =
      EnumSet.of(
          Status.PENDING_DISPATCH,
          Status.QUEUED,
          Status.RUNNING,
          Status.RETRYING,
          Status.CANCEL_REQUESTED,
          Status.PUBLISH_FAILED);

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "user_id", length = 36, nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Type type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "source_file_id", length = 36)
  private String fileId;

  @Column(length = 2048)
  private String url;

  @Column(name = "folder_id", length = 36)
  private String folderId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "request_key", length = 100)
  private String requestKey;

  @Column(name = "request_hash", length = 64)
  private String requestHash;

  @Column(name = "lease_token", length = 36)
  private String leaseToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(nullable = false, columnDefinition = "integer default 0")
  private int attempts;

  @Column(name = "max_attempts", nullable = false, columnDefinition = "integer default 2")
  private int maxAttempts = 2;

  @Column(nullable = false, columnDefinition = "integer default 0")
  private int progress;

  @Column(name = "reserved_bytes", nullable = false, columnDefinition = "bigint default 0")
  private long reservedBytes;

  @Column(name = "error_code", length = 50)
  private String errorCode;

  @org.hibernate.annotations.Nationalized
  @Column(name = "error_message", length = 300)
  private String errorMessage;

  protected MediaJob() {}

  public MediaJob(String id, String user, Type type, String file, String url, String folder) {
    this.id = id;
    userId = user;
    this.type = type;
    fileId = file;
    this.url = url;
    folderId = folder;
    status = Status.QUEUED;
    createdAt = updatedAt = Instant.now();
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public Type getType() {
    return type;
  }

  public Status getStatus() {
    return status;
  }

  public String getFileId() {
    return fileId;
  }

  public String getUrl() {
    return url;
  }

  public String getFolderId() {
    return folderId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getRequestKey() {
    return requestKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public int getProgress() {
    return progress;
  }

  public long getReservedBytes() {
    return reservedBytes;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public boolean terminal() {
    return !ACTIVE.contains(status);
  }

  public void submit(String key, String hash, long bytes, int max) {
    requestKey = key;
    requestHash = hash;
    reservedBytes = bytes;
    maxAttempts = max;
    change(Status.PENDING_DISPATCH);
  }

  public void claim(String token, Instant until) {
    leaseToken = token;
    leaseUntil = until;
    attempts++;
    progress = 0;
    errorCode = null;
    errorMessage = null;
    change(Status.RUNNING);
  }

  public void requestCancel() {
    change(status == Status.RUNNING ? Status.CANCEL_REQUESTED : Status.CANCELLED);
    if (status == Status.CANCELLED) release();
  }

  public void complete() {
    progress = 100;
    change(Status.COMPLETED);
    release();
  }

  public void cancel() {
    change(Status.CANCELLED);
    release();
  }

  public void failure(String code, String message, Instant retry) {
    errorCode = code;
    errorMessage = message;
    nextAttemptAt = retry;
    change(retry == null ? Status.FAILED : Status.RETRYING);
    leaseToken = null;
    leaseUntil = null;
    if (retry == null) reservedBytes = 0;
  }

  public void reserve(long bytes) {
    reservedBytes = bytes;
  }

  public void redispatch() {
    updatedAt = Instant.now();
  }

  private void release() {
    leaseToken = null;
    leaseUntil = null;
    reservedBytes = 0;
    nextAttemptAt = null;
  }

  private void change(Status state) {
    status = state;
    updatedAt = Instant.now();
  }
}

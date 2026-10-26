package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "upload_sessions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_upload_owner",
            columnNames = {"user_id", "upload_id"}),
    indexes = {
      @Index(name = "idx_upload_owner_status", columnList = "user_id,status"),
      @Index(name = "idx_upload_expiry", columnList = "expires_at")
    })
public class UploadSession {
  public enum Status {
    OPEN,
    MERGING,
    COMPLETED,
    ABORTED
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "user_id", length = 36, nullable = false)
  private String userId;

  @Column(name = "upload_id", length = 36, nullable = false)
  private String uploadId;

  @Column(name = "folder_id", length = 36)
  private String folderId;

  @org.hibernate.annotations.Nationalized
  @Column(length = 255)
  private String filename;

  @Column(name = "total_chunks", nullable = false)
  private int totalChunks;

  @Column(name = "expected_bytes")
  private Long expectedBytes;

  @Column(name = "reserved_bytes", nullable = false)
  private long reservedBytes;

  @Column(name = "result_file_id", length = 36, nullable = false)
  private String resultFileId;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private Status status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "lease_token", length = 36)
  private String leaseToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Column(nullable = false)
  private boolean cleaned;

  public boolean isCleaned() {
    return cleaned;
  }

  public void cleaned() {
    cleaned = true;
  }

  protected UploadSession() {}

  public UploadSession(
      String user,
      String upload,
      String folder,
      String filename,
      int chunks,
      Long bytes,
      long reserve,
      Instant expires) {
    this.id = key(user, upload);
    userId = user;
    uploadId = upload;
    folderId = folder;
    this.filename = filename;
    totalChunks = chunks;
    expectedBytes = bytes;
    reservedBytes = reserve;
    expiresAt = expires;
    status = Status.OPEN;
    resultFileId = UUID.randomUUID().toString();
  }

  public static String key(String user, String upload) {
    return UUID.nameUUIDFromBytes((user + ":" + upload).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getUploadId() {
    return uploadId;
  }

  public String getFolderId() {
    return folderId;
  }

  public String getFilename() {
    return filename;
  }

  public int getTotalChunks() {
    return totalChunks;
  }

  public Long getExpectedBytes() {
    return expectedBytes;
  }

  public long getReservedBytes() {
    return reservedBytes;
  }

  public String getResultFileId() {
    return resultFileId;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void contentHash(String value) {
    contentHash = value;
  }

  public void touch(Instant until) {
    expiresAt = until;
  }

  public void prepare(String name, String folder) {
    filename = name;
    folderId = folder;
  }

  public void claim(String token, Instant until) {
    status = Status.MERGING;
    leaseToken = token;
    leaseUntil = until;
  }

  public void reopen() {
    status = Status.OPEN;
    leaseToken = null;
    leaseUntil = null;
  }

  public void complete() {
    status = Status.COMPLETED;
    reservedBytes = 0;
    leaseToken = null;
    leaseUntil = null;
  }

  public void abort() {
    status = Status.ABORTED;
    reservedBytes = 0;
    leaseToken = null;
    leaseUntil = null;
  }
}

package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "file_shares",
    uniqueConstraints = @UniqueConstraint(name = "uk_file_share_token", columnNames = "token_hash"),
    indexes = {
      @Index(name = "idx_share_owner_file", columnList = "user_id,file_id,created_at"),
      @Index(name = "idx_share_expiry", columnList = "expires_at")
    })
public class FileShare {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "user_id", length = 36, nullable = false)
  private String userId;

  @Column(name = "file_id", length = 36, nullable = false)
  private String fileId;

  @Column(name = "token_hash", length = 64, nullable = false)
  private String tokenHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "file_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_share_file"))
  private MediaFile fileReference;

  protected FileShare() {}

  public FileShare(
      String id,
      String userId,
      String fileId,
      String tokenHash,
      Instant createdAt,
      Instant expiresAt) {
    this.id = id;
    this.userId = userId;
    this.fileId = fileId;
    this.tokenHash = tokenHash;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getFileId() {
    return fileId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean active(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  public void revoke(Instant now) {
    if (revokedAt == null) revokedAt = now;
  }
}

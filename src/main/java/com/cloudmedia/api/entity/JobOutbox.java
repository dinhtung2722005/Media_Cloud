package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "job_outbox",
    indexes = @Index(name = "idx_outbox_due", columnList = "sent,next_attempt_at,lease_until"))
public class JobOutbox {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false)
  private boolean sent;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "lease_token", length = 36)
  private String leaseToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(nullable = false)
  private int attempts;

  protected JobOutbox() {}

  public JobOutbox(String id) {
    this.id = id;
    this.nextAttemptAt = Instant.now();
  }

  public String getId() {
    return id;
  }

  public boolean isSent() {
    return sent;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public int getAttempts() {
    return attempts;
  }

  public void reschedule(Instant when) {
    sent = false;
    nextAttemptAt = when;
    leaseToken = null;
    leaseUntil = null;
  }

  public void stop() {
    sent = true;
    leaseToken = null;
    leaseUntil = null;
  }
}

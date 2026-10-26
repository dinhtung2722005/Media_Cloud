package com.cloudmedia.api.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "storage_identities")
public class StorageIdentity {
  @Id
  @Column(length = 20)
  private String id;

  @Column(length = 36, nullable = false)
  private String token;

  protected StorageIdentity() {}

  public StorageIdentity(String id, String token) {
    this.id = id;
    this.token = token;
  }

  public String getToken() {
    return token;
  }
}

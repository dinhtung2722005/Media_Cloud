package com.cloudmedia.api.entity;

import jakarta.persistence.*;

@Entity
@Table(
    name = "MediaFiles",
    indexes = {
      @Index(name = "idx_file_owner_folder", columnList = "user_id,folder_id"),
      @Index(name = "idx_file_status", columnList = "status"),
      @Index(name = "idx_file_listing", columnList = "user_id,folder_id,status,title,id")
    })
public class MediaFile {

  @Id
  @Column(columnDefinition = "VARCHAR(36)")
  private String id;

  @org.hibernate.annotations.Nationalized
  @Column(nullable = false)
  private String title;

  @org.hibernate.annotations.Nationalized
  @Column(length = 255)
  private String searchTitle;

  @PrePersist
  @PreUpdate
  void indexName() {
    searchTitle = com.cloudmedia.api.domain.InputRules.normalized(title);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public String getSearchTitle() {
    return searchTitle;
  }

  @Column(length = 500, nullable = false)
  private String storagePath;

  @Column(length = 64)
  private String checksum;

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(String value) {
    checksum = value;
  }

  @Column(length = 10, nullable = false)
  private String extension;

  @Column(nullable = false)
  private Long fileSize;

  @Column(name = "folder_id", columnDefinition = "VARCHAR(36)")
  private String folderId;

  @Column(columnDefinition = "VARCHAR(36)")
  private String userId;

  @com.fasterxml.jackson.annotation.JsonIgnore
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "folder_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_media_folder"))
  private Folder folderReference;

  @Column(length = 20, nullable = false)
  private String status;

  @Column(length = 20, nullable = false)
  private String source;

  public MediaFile() {}

  public MediaFile(
      String id,
      String title,
      String storagePath,
      String extension,
      Long fileSize,
      String folderId,
      String userId,
      String status,
      String source) {
    this.id = id;
    this.title = title;
    this.storagePath = storagePath;
    this.extension = extension;
    this.fileSize = fileSize;
    this.folderId = folderId;
    this.userId = userId;
    this.status = status;
    this.source = source;
  }

  // ==========================================
  // GETTERS VÀ SETTERS
  // ==========================================
  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public String getStoragePath() {
    return storagePath;
  }

  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  public String getExtension() {
    return extension;
  }

  public void setExtension(String extension) {
    this.extension = extension;
  }

  public Long getFileSize() {
    return fileSize;
  }

  public void setFileSize(Long fileSize) {
    this.fileSize = fileSize;
  }

  public String getFolderId() {
    return folderId;
  }

  public void setFolderId(String folderId) {
    this.folderId = folderId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}

package com.cloudmedia.api.entity;

import jakarta.persistence.*;

@Entity
@Table(
    name = "Folders",
    indexes = {
      @Index(name = "idx_folder_owner_parent", columnList = "user_id,parent_folder_id"),
      @Index(name = "idx_folder_listing", columnList = "user_id,parent_folder_id,folder_name,id")
    })
public class Folder {
  @Id
  @Column(columnDefinition = "VARCHAR(36)")
  private String id;

  @org.hibernate.annotations.Nationalized
  @Column(nullable = false)
  private String folderName;

  @org.hibernate.annotations.Nationalized
  @Column(length = 255)
  private String searchName;

  @PrePersist
  @PreUpdate
  void indexName() {
    searchName = com.cloudmedia.api.domain.InputRules.normalized(folderName);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public String getSearchName() {
    return searchName;
  }

  @Column(name = "parent_folder_id", columnDefinition = "VARCHAR(36)")
  private String parentFolderId; // Trỏ về Id của bảng này (NULL nếu ở gốc)

  @Column(columnDefinition = "VARCHAR(36)")
  private String userId; // Trỏ về bảng Users

  @com.fasterxml.jackson.annotation.JsonIgnore
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "parent_folder_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_folder_parent"))
  private Folder parentReference;

  public Folder(String id, String folderName, String parentFolderId, String userId) {
    this.id = id;
    this.folderName = folderName;
    this.parentFolderId = parentFolderId;
    this.userId = userId;
  }

  public Folder() {}

  public String getId() {
    return id;
  }

  public String getFolderName() {
    return folderName;
  }

  public String getParentFolderId() {
    return parentFolderId;
  }

  public String getUserId() {
    return userId;
  }

  public void setId(String id) {
    this.id = id;
  }

  public void setFolderName(String folderName) {
    this.folderName = folderName;
  }

  public void setParentFolderId(String parentFolderId) {
    this.parentFolderId = parentFolderId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  // Getters và Setters...
}

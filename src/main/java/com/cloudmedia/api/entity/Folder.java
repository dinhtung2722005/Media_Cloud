package com.cloudmedia.api.entity;
import jakarta.persistence.*;
@Entity
@Table(name = "Folders")

public class Folder {
    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(nullable = false)
    private String folderName;

    @Column(columnDefinition = "VARCHAR(36)")
    private String parentFolderId; // Trỏ về Id của bảng này (NULL nếu ở gốc)

    @Column(columnDefinition = "VARCHAR(36)")
    private String userId; // Trỏ về bảng Users

    public Folder(String id, String folderName, String parentFolderId, String userId) {
        this.id = id;
        this.folderName = folderName;
        this.parentFolderId = parentFolderId;
        this.userId = userId;
    }
    public Folder() {
    }
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
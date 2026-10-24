package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "media_jobs")
public class MediaJob {
    public enum Type { CONVERT_MP3, YOUTUBE }
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, PUBLISH_FAILED }

    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", length = 36, nullable = false) private String userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "source_file_id", length = 36) private String fileId;
    @Column(length = 2048) private String url;
    @Column(name = "folder_id", length = 36) private String folderId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected MediaJob() {}

    public MediaJob(String id, String userId, Type type, String fileId, String url, String folderId) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.fileId = fileId;
        this.url = url;
        this.folderId = folderId;
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public String getFileId() { return fileId; }
    public String getUrl() { return url; }
    public String getFolderId() { return folderId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

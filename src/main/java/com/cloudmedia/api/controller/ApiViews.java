package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.*;
import java.time.Instant;

public final class ApiViews {
  private ApiViews() {}

  public static FileView file(MediaFile value) {
    return FileView.from(value);
  }

  public static FolderView folder(Folder value) {
    return FolderView.from(value);
  }

  public record FileView(
      String id,
      String title,
      String extension,
      long fileSize,
      String folderId,
      String status,
      String source) {
    public static FileView from(MediaFile file) {
      return new FileView(
          file.getId(),
          file.getTitle(),
          file.getExtension(),
          file.getFileSize() == null ? 0 : file.getFileSize(),
          file.getFolderId(),
          file.getStatus(),
          file.getSource());
    }
  }

  public record FolderView(String id, String folderName, String parentFolderId) {
    public static FolderView from(Folder folder) {
      return new FolderView(folder.getId(), folder.getFolderName(), folder.getParentFolderId());
    }
  }

  public record JobView(
      String jobId,
      MediaJob.Type type,
      MediaJob.Status status,
      String resultFileId,
      Instant createdAt,
      Instant updatedAt,
      int progress,
      int attempts,
      int maxAttempts,
      String errorCode,
      String message,
      Instant nextAttemptAt) {
    public static JobView from(MediaJob job) {
      return new JobView(
          job.getId(),
          job.getType(),
          job.getStatus(),
          job.getStatus() == MediaJob.Status.COMPLETED ? job.getId() : null,
          job.getCreatedAt(),
          job.getUpdatedAt(),
          job.getProgress(),
          job.getAttempts(),
          job.getMaxAttempts(),
          job.getErrorCode(),
          job.getErrorMessage(),
          job.getNextAttemptAt());
    }
  }
}

package com.cloudmedia.api.service;

import com.cloudmedia.api.cache.DriveCache;
import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FolderService {
  private final FolderRepository folders;
  private final MediaFileRepository files;
  private final DriveCache cache;
  private final AccountTransactions accounts;
  private final MediaJobRepository jobs;
  private final UploadSessionRepository uploads;

  public FolderService(
      FolderRepository folders,
      MediaFileRepository files,
      DriveCache cache,
      AccountTransactions accounts,
      MediaJobRepository jobs,
      UploadSessionRepository uploads) {
    this.folders = folders;
    this.files = files;
    this.cache = cache;
    this.accounts = accounts;
    this.jobs = jobs;
    this.uploads = uploads;
  }

  public Folder createFolder(String name, String parent, String user) {
    String valid = InputRules.name(name),
        destination = parent == null || parent.isBlank() ? null : parent;
    return accounts.locked(
        user,
        () -> {
          if (destination != null) getFolderById(destination, user);
          Folder result =
              folders.saveAndFlush(
                  new Folder(UUID.randomUUID().toString(), valid, destination, user));
          cache.invalidateAfterCommit(user);
          return result;
        });
  }

  public List<Folder> getFolders(String user, String parent) {
    return parent == null || parent.isBlank()
        ? folders.findByUserIdAndParentFolderIdIsNull(user)
        : folders.findByUserIdAndParentFolderId(user, parent);
  }

  public Folder getFolderById(String id, String user) {
    return folders
        .findById(id)
        .filter(f -> user.equals(f.getUserId()))
        .orElseThrow(DomainException::missing);
  }

  public Folder updateFolderName(String id, String name, String user) {
    String valid = InputRules.name(name);
    return accounts.locked(
        user,
        () -> {
          Folder found = getFolderById(id, user);
          found.setFolderName(valid);
          Folder result = folders.saveAndFlush(found);
          cache.invalidateAfterCommit(user);
          return result;
        });
  }

  public void deleteFolder(String id, String user) {
    accounts.locked(
        user,
        () -> {
          Folder found = getFolderById(id, user);
          if (jobs.existsByUserIdAndFolderIdAndStatusIn(user, id, MediaJob.ACTIVE)
              || uploads.existsByUserIdAndFolderIdAndStatusInAndExpiresAtAfter(
                  user, id, QuotaService.UPLOAD_ACTIVE, Instant.now()))
            throw DomainException.conflict(
                "FOLDER_BUSY", "Thư mục đang có tác vụ hoặc upload hoạt động");
          if (!folders.findByUserIdAndParentFolderId(user, id).isEmpty()
              || !files.findByUserIdAndFolderId(user, id).isEmpty())
            throw DomainException.conflict("FOLDER_NOT_EMPTY", "Chỉ có thể xóa thư mục trống");
          folders.delete(found);
          folders.flush();
          cache.invalidateAfterCommit(user);
        });
  }
}

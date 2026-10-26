package com.cloudmedia.api.service;

import com.cloudmedia.api.cache.DriveCache;
import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
  private final MediaFileRepository files;
  private final DriveCache cache;
  private final AccountTransactions accounts;
  private final UploadCoordinator uploads;
  private final FileStorage storage;
  private final MediaJobRepository jobs;
  private final FileShareRepository shares;

  public FileService(
      MediaFileRepository files,
      DriveCache cache,
      AccountTransactions accounts,
      UploadCoordinator uploads,
      FileStorage storage,
      MediaJobRepository jobs,
      FileShareRepository shares) {
    this.files = files;
    this.cache = cache;
    this.accounts = accounts;
    this.uploads = uploads;
    this.storage = storage;
    this.jobs = jobs;
    this.shares = shares;
  }

  public MediaFile uploadFile(MultipartFile file, String folder, String user) throws IOException {
    return uploadFile(file, folder, user, null);
  }

  public MediaFile uploadFile(MultipartFile file, String folder, String user, String requestKey)
      throws IOException {
    String name = InputRules.name(file.getOriginalFilename()),
        extension = MediaService.fileExtension(name),
        key = InputRules.key(requestKey);
    String id =
        key == null
            ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes((user + ":direct:" + key).getBytes(StandardCharsets.UTF_8))
                .toString();
    String checksum = null;
    if (key != null) {
      var hash = digest();
      try (InputStream input = new java.security.DigestInputStream(file.getInputStream(), hash)) {
        input.transferTo(OutputStream.nullOutputStream());
      }
      checksum = java.util.HexFormat.of().formatHex(hash.digest());
    }
    uploads.direct(user, id, name, file.getSize(), folder, checksum);
    UploadCoordinator.Claim claim = uploads.claim(user, id, name, 0, folder);
    if (claim.completed() != null) return claim.completed();
    Path output = null;
    try {
      output = storage.allocate(claim.token(), extension);
      var hash = digest();
      try (InputStream input = new java.security.DigestInputStream(file.getInputStream(), hash)) {
        Files.copy(input, output);
      }
      long size = Files.size(output);
      MediaFile result =
          new MediaFile(
              claim.session().getResultFileId(),
              name,
              storage.key(output),
              extension,
              size,
              claim.session().getFolderId(),
              user,
              "Ready",
              "Local_Upload");
      result.setChecksum(java.util.HexFormat.of().formatHex(hash.digest()));
      MediaFile saved = uploads.commit(claim, result);
      if (!saved.getStoragePath().equals(result.getStoragePath())) storage.cleanup(output);
      return saved;
    } catch (IOException | RuntimeException error) {
      uploads.discard(claim, output, storage);
      try {
        if (key == null) uploads.abort(user, id);
        else uploads.failed(claim);
      } catch (RuntimeException ignored) {
      }
      throw error;
    }
  }

  public MediaFile requireOwnedFile(String id, String user) {
    return files
        .findById(id)
        .filter(f -> user.equals(f.getUserId()) && "Ready".equals(f.getStatus()))
        .orElseThrow(DomainException::missing);
  }

  private static java.security.MessageDigest digest() {
    try {
      return java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public FileResourceWrapper getFileForResponse(String id, String user) throws IOException {
    MediaFile file = requireOwnedFile(id, user);
    Path path = storage.resolve(file.getStoragePath());
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
      throw DomainException.missing();
    String mime = Files.probeContentType(path);
    return new FileResourceWrapper(
        new UrlResource(path.toUri()),
        mime == null ? "application/octet-stream" : mime,
        file.getTitle());
  }

  public boolean deleteAndTry(String id, String user) {
    MediaFile file =
        accounts.locked(
            user,
            () -> {
              MediaFile found =
                  files
                      .findById(id)
                      .filter(f -> user.equals(f.getUserId()))
                      .orElseThrow(DomainException::missing);
              if (jobs.existsByUserIdAndFileIdAndStatusIn(user, id, MediaJob.ACTIVE))
                throw DomainException.conflict("FILE_BUSY", "Tệp đang được một tác vụ sử dụng");
              found.setStatus("Deleting");
              files.saveAndFlush(found);
              cache.invalidateAfterCommit(user);
              return found;
            });
    return retryDeletion(file);
  }

  public void deleteFile(String id, String user) {
    if (!deleteAndTry(id, user))
      throw new DomainException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "FILE_DELETE_PENDING",
          "Đã ghi nhận xóa; hệ thống sẽ thử dọn dữ liệu lại");
  }

  public boolean retryDeletion(MediaFile file) {
    try {
      storage.delete(file.getStoragePath());
    } catch (IOException | RuntimeException error) {
      return false;
    }
    accounts.locked(
        file.getUserId(),
        () ->
            files
                .findById(file.getId())
                .filter(f -> "Deleting".equals(f.getStatus()))
                .ifPresent(
                    found -> {
                      shares.deleteForFile(found.getId());
                      files.delete(found);
                      files.flush();
                      cache.invalidateAfterCommit(file.getUserId());
                    }));
    return true;
  }

  public void renameFile(String id, String name, String user) {
    String valid = InputRules.name(name);
    accounts.locked(
        user,
        () -> {
          MediaFile file = requireOwnedFile(id, user);
          if (!MediaService.fileExtension(valid).equalsIgnoreCase(file.getExtension()))
            throw DomainException.invalid("Giữ nguyên phần mở rộng khi đổi tên tệp");
          file.setTitle(valid);
          files.saveAndFlush(file);
          cache.invalidateAfterCommit(user);
        });
  }

  public record FileResourceWrapper(Resource resource, String mimeType, String filename) {
    public Resource getResource() {
      return resource;
    }

    public String getMimeType() {
      return mimeType;
    }

    public String getFilename() {
      return filename;
    }
  }
}

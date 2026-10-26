package com.cloudmedia.api.service;

import com.cloudmedia.api.cache.DriveCache;
import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UploadCoordinator {
  public record Claim(UploadSession session, String token, MediaFile completed) {}

  private final UploadSessionRepository uploads;
  private final MediaFileRepository files;
  private final FolderRepository folders;
  private final AccountTransactions accounts;
  private final QuotaService quotas;
  private final DriveCache cache;
  private final long lifetime;
  private final long temporaryLimit;
  private final int activeLimit;

  public UploadCoordinator(
      UploadSessionRepository uploads,
      MediaFileRepository files,
      FolderRepository folders,
      AccountTransactions accounts,
      QuotaService quotas,
      DriveCache cache,
      @Value("${app.upload.expire-after-ms:86400000}") long lifetime,
      @Value("${app.upload.max-temp-bytes-per-user:1073741824}") long temporaryLimit,
      @Value("${app.upload.max-active-per-user:5}") int activeLimit) {
    this.uploads = uploads;
    this.files = files;
    this.folders = folders;
    this.accounts = accounts;
    this.quotas = quotas;
    this.cache = cache;
    if (lifetime < 1 || temporaryLimit < 1 || activeLimit < 1)
      throw new IllegalArgumentException("Upload limits invalid");
    this.lifetime = lifetime;
    this.temporaryLimit = temporaryLimit;
    this.activeLimit = activeLimit;
  }

  public UploadSession initialize(
      String user, String upload, String filename, long bytes, int chunks, String folder) {
    return initialize(user, upload, filename, bytes, chunks, folder, null);
  }

  public UploadSession initialize(
      String user,
      String upload,
      String filename,
      long bytes,
      int chunks,
      String folder,
      String checksum) {
    if (checksum != null && !checksum.matches("[0-9a-fA-F]{64}"))
      throw DomainException.invalid("SHA-256 không hợp lệ");
    String expectedHash = checksum == null ? null : checksum.toLowerCase(java.util.Locale.ROOT);
    InputRules.uuid(user);
    InputRules.uuid(upload);
    String name = InputRules.name(filename);
    if (bytes < 0
        || bytes > temporaryLimit
        || chunks != Math.max(1, (bytes + 5242879) / 5242880)
        || chunks > 10000) throw DomainException.invalid("Kích thước hoặc số mảnh không hợp lệ");
    return accounts.locked(
        user,
        () -> {
          Optional<UploadSession> old = uploads.findById(UploadSession.key(user, upload));
          if (old.isPresent()) {
            UploadSession session = old.get();
            usable(session);
            if (!Objects.equals(expectedHash, session.getContentHash())
                || !Objects.equals(name, session.getFilename())
                || !Objects.equals(normalize(folder), session.getFolderId())
                || !Objects.equals(bytes, session.getExpectedBytes())
                || session.getTotalChunks() != chunks)
              throw DomainException.conflict(
                  "UPLOAD_CONFLICT", "Mã upload đã được dùng cho tệp khác");
            return session;
          }
          UploadSession session =
              create(user, upload, name, bytes, chunks, normalize(folder), bytes);
          session.contentHash(expectedHash);
          return uploads.saveAndFlush(session);
        });
  }

  public UploadSession direct(
      String user, String upload, String filename, long bytes, String folder) {
    return direct(user, upload, filename, bytes, folder, null);
  }

  public UploadSession direct(
      String user, String upload, String filename, long bytes, String folder, String checksum) {
    return accounts.locked(
        user,
        () -> {
          UploadSession old = uploads.findById(UploadSession.key(user, upload)).orElse(null);
          if (old != null) {
            usable(old);
            if (!Objects.equals(old.getFilename(), filename)
                || !Objects.equals(old.getExpectedBytes(), bytes)
                || !Objects.equals(old.getFolderId(), normalize(folder))
                || !Objects.equals(old.getContentHash(), checksum))
              throw DomainException.conflict("UPLOAD_CONFLICT", "Khóa tải lên đã được sử dụng");
            return old;
          }
          if (bytes < 0 || bytes > temporaryLimit)
            throw DomainException.invalid("Tệp vượt giới hạn upload");
          UploadSession session =
              create(user, upload, InputRules.name(filename), bytes, 0, normalize(folder), bytes);
          session.contentHash(checksum);
          return uploads.saveAndFlush(session);
        });
  }

  private UploadSession create(
      String user,
      String upload,
      String name,
      Long bytes,
      int chunks,
      String folder,
      long reserve) {
    requireFolder(folder, user);
    if (uploads.countByUserIdAndStatusInAndExpiresAtAfter(
            user, QuotaService.UPLOAD_ACTIVE, Instant.now())
        >= activeLimit)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_QUOTA", "Đã đạt giới hạn upload đang mở");
    quotas.reserve(user, reserve);
    return uploads.saveAndFlush(
        new UploadSession(
            user,
            upload,
            folder,
            name,
            chunks,
            bytes,
            reserve,
            Instant.now().plusMillis(lifetime)));
  }

  public void chunk(String user, String upload, int total, Consumer<UploadSession> write) {
    accounts.locked(
        user,
        () -> {
          UploadSession session =
              uploads
                  .findById(UploadSession.key(user, upload))
                  .orElseGet(
                      () ->
                          create(
                              user,
                              upload,
                              null,
                              null,
                              total,
                              null,
                              Math.min(temporaryLimit, total * 5242880L)));
          usable(session);
          if (session.getTotalChunks() != total)
            throw DomainException.conflict("UPLOAD_CONFLICT", "Số mảnh không khớp");
          if (session.getStatus() == UploadSession.Status.COMPLETED) return;
          if (session.getStatus() != UploadSession.Status.OPEN)
            throw DomainException.conflict("UPLOAD_BUSY", "Tệp đang được hoàn tất");
          write.accept(session);
          session.touch(Instant.now().plusMillis(lifetime));
          uploads.saveAndFlush(session);
        });
  }

  public Claim claim(String user, String upload, String filename, int total, String folder) {
    return accounts.locked(
        user,
        () -> {
          UploadSession session = get(user, upload);
          usable(session);
          if (session.getTotalChunks() != total)
            throw DomainException.conflict("UPLOAD_CONFLICT", "Số mảnh không khớp");
          if (session.getStatus() == UploadSession.Status.MERGING
              && session.getLeaseUntil() != null
              && session.getLeaseUntil().isAfter(Instant.now()))
            throw DomainException.conflict("UPLOAD_BUSY", "Một yêu cầu khác đang hoàn tất tệp này");
          String name = InputRules.name(filename), parent = normalize(folder);
          if (session.getFilename() != null
              && (!session.getFilename().equals(name)
                  || !Objects.equals(session.getFolderId(), parent)))
            throw DomainException.conflict(
                "UPLOAD_CONFLICT", "Thông tin hoàn tất không khớp với upload");
          if (session.getStatus() == UploadSession.Status.COMPLETED)
            return new Claim(session, null, result(session));
          requireFolder(parent, user);
          session.prepare(name, parent);
          String token = UUID.randomUUID().toString();
          session.claim(token, Instant.now().plusSeconds(1800));
          session.touch(Instant.now().plusMillis(Math.max(lifetime, 1800000)));
          uploads.saveAndFlush(session);
          return new Claim(session, token, null);
        });
  }

  public MediaFile commit(Claim claim, MediaFile result) {
    return accounts.locked(
        claim.session().getUserId(),
        () -> {
          UploadSession session = get(claim.session().getUserId(), claim.session().getUploadId());
          if (session.getStatus() == UploadSession.Status.COMPLETED) return result(session);
          if (session.getStatus() != UploadSession.Status.MERGING
              || !Objects.equals(claim.token(), session.getLeaseToken())
              || !session.getLeaseUntil().isAfter(Instant.now()))
            throw DomainException.conflict("UPLOAD_LEASE_LOST", "Lượt hoàn tất đã hết quyền chạy");
          requireFolder(session.getFolderId(), session.getUserId());
          if (session.getExpectedBytes() != null
              && session.getExpectedBytes() != result.getFileSize().longValue())
            throw DomainException.invalid("Kích thước tệp sau khi ghép không khớp");
          if (session.getContentHash() != null
              && !session.getContentHash().equals(result.getChecksum()))
            throw DomainException.invalid("SHA-256 của tệp không khớp");
          if (!session.getResultFileId().equals(result.getId())
              || !session.getUserId().equals(result.getUserId())
              || !Objects.equals(session.getFolderId(), result.getFolderId()))
            throw DomainException.invalid("Kết quả upload không hợp lệ");
          quotas.finish(session.getUserId(), result.getFileSize(), session.getReservedBytes());
          files.saveAndFlush(result);
          session.complete();
          uploads.saveAndFlush(session);
          cache.invalidateAfterCommit(session.getUserId());
          return result;
        });
  }

  public void failed(Claim claim) {
    accounts.locked(
        claim.session().getUserId(),
        () -> {
          UploadSession session = get(claim.session().getUserId(), claim.session().getUploadId());
          if (session.getStatus() == UploadSession.Status.MERGING
              && Objects.equals(claim.token(), session.getLeaseToken())) {
            session.reopen();
            uploads.saveAndFlush(session);
          }
        });
  }

  public void discard(Claim claim, java.nio.file.Path output, FileStorage storage) {
    if (output == null) return;
    try {
      String key = storage.key(output);
      accounts.locked(
          claim.session().getUserId(),
          () -> {
            if (!files.existsByStoragePath(key)) storage.cleanup(output);
          });
    } catch (java.io.IOException | RuntimeException unavailable) {
      org.slf4j.LoggerFactory.getLogger(UploadCoordinator.class)
          .warn("Giữ staging để đối soát khi chưa xác định được kết quả commit upload");
    }
  }

  public void abort(String user, String upload) {
    accounts.locked(
        user,
        () -> {
          UploadSession session = get(user, upload);
          if (session.getStatus() == UploadSession.Status.COMPLETED)
            throw DomainException.conflict("UPLOAD_COMPLETED", "Tệp đã hoàn tất");
          session.abort();
          uploads.saveAndFlush(session);
        });
  }

  public UploadSession get(String user, String upload) {
    InputRules.uuid(user);
    InputRules.uuid(upload);
    return uploads
        .findById(UploadSession.key(user, upload))
        .filter(s -> s.getUserId().equals(user) && s.getUploadId().equals(upload))
        .orElseThrow(DomainException::missing);
  }

  private MediaFile result(UploadSession session) {
    return files
        .findById(session.getResultFileId())
        .filter(f -> session.getUserId().equals(f.getUserId()) && "Ready".equals(f.getStatus()))
        .orElseThrow(
            () ->
                new DomainException(
                    HttpStatus.GONE, "UPLOAD_RESULT_DELETED", "Tệp của upload này đã được xóa"));
  }

  private void usable(UploadSession session) {
    if (session.getStatus() == UploadSession.Status.ABORTED
        || session.getStatus() != UploadSession.Status.COMPLETED
            && !session.getExpiresAt().isAfter(Instant.now()))
      throw new DomainException(HttpStatus.GONE, "UPLOAD_EXPIRED", "Upload đã hết hạn hoặc đã hủy");
  }

  private void requireFolder(String folder, String user) {
    if (folder != null)
      folders
          .findById(folder)
          .filter(f -> user.equals(f.getUserId()))
          .orElseThrow(DomainException::missing);
  }

  private String normalize(String id) {
    return id == null || id.isBlank() ? null : id;
  }
}

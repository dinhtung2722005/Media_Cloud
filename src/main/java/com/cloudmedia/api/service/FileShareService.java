package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.FileShareRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class FileShareService {
  public record Created(String id, String path, Instant createdAt, Instant expiresAt) {}

  public record View(String id, Instant createdAt, Instant expiresAt, String status) {}

  public record PublicFile(String title, String extension, long fileSize, Instant expiresAt) {}

  public record Access(FileShare share, MediaFile file) {}

  private final FileShareRepository shares;
  private final FileService files;
  private final AccountTransactions accounts;
  private final SecureRandom random = new SecureRandom();
  private final long defaultHours, maxHours;

  public FileShareService(
      FileShareRepository shares,
      FileService files,
      AccountTransactions accounts,
      @Value("${app.share.default-hours:24}") long defaultHours,
      @Value("${app.share.max-hours:720}") long maxHours) {
    if (defaultHours < 1 || defaultHours > maxHours || maxHours > 720)
      throw new IllegalArgumentException("Share lifetime must be 1..720 hours");
    this.shares = shares;
    this.files = files;
    this.accounts = accounts;
    this.defaultHours = defaultHours;
    this.maxHours = maxHours;
  }

  public Created create(String fileId, String user, Long requestedHours) {
    long hours = requestedHours == null ? defaultHours : requestedHours;
    if (hours < 1 || hours > maxHours)
      throw DomainException.invalid("Thời hạn liên kết phải từ 1 đến " + maxHours + " giờ");
    return accounts.locked(
        user,
        () -> {
          files.requireOwnedFile(fileId, user);
          Instant now = Instant.now();
          if (shares.countByUserIdAndFileIdAndRevokedAtIsNullAndExpiresAtAfter(user, fileId, now)
                  >= 10
              || shares.countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(user, now) >= 1000)
            throw DomainException.conflict(
                "SHARE_LIMIT",
                "Đã đạt giới hạn liên kết hoạt động. Thu hồi liên kết cũ trước khi tạo thêm.");
          byte[] secret = new byte[32];
          random.nextBytes(secret);
          String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
          FileShare share =
              shares.saveAndFlush(
                  new FileShare(
                      UUID.randomUUID().toString(),
                      user,
                      fileId,
                      hash(token),
                      now,
                      now.plusSeconds(hours * 3600)));
          return new Created(
              share.getId(), "/s/" + token, share.getCreatedAt(), share.getExpiresAt());
        });
  }

  public List<View> list(String fileId, String user) {
    files.requireOwnedFile(fileId, user);
    Instant now = Instant.now();
    return shares.listForOwner(user, fileId, now, PageRequest.of(0, 50)).stream()
        .map(
            s ->
                new View(
                    s.getId(),
                    s.getCreatedAt(),
                    s.getExpiresAt(),
                    s.getRevokedAt() != null ? "REVOKED" : s.active(now) ? "ACTIVE" : "EXPIRED"))
        .toList();
  }

  public void revoke(String fileId, String id, String user) {
    accounts.locked(
        user,
        () -> {
          files.requireOwnedFile(fileId, user);
          FileShare share =
              shares
                  .findById(id)
                  .filter(s -> user.equals(s.getUserId()) && fileId.equals(s.getFileId()))
                  .orElseThrow(DomainException::missing);
          share.revoke(Instant.now());
          shares.saveAndFlush(share);
        });
  }

  public Access access(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw DomainException.missing();
    FileShare share =
        shares
            .findByTokenHash(hash(token))
            .filter(s -> s.active(Instant.now()))
            .orElseThrow(DomainException::missing);
    return new Access(share, files.requireOwnedFile(share.getFileId(), share.getUserId()));
  }

  public PublicFile metadata(String token) {
    Access access = access(token);
    MediaFile file = access.file();
    return new PublicFile(
        file.getTitle(), file.getExtension(), file.getFileSize(), access.share().getExpiresAt());
  }

  public FileService.FileResourceWrapper resource(String token) throws java.io.IOException {
    Access access = access(token);
    return files.getFileForResponse(access.file().getId(), access.share().getUserId());
  }

  private static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}

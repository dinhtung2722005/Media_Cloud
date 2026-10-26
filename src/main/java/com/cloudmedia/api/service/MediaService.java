package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
  private static final long CHUNK_SIZE = 5L * 1024 * 1024;
  private final UploadCoordinator uploads;
  private final FileStorage storage;

  @Value("${app.storage.temp-dir:./storage/temp}")
  private String tempStorageDir = "./storage/temp";

  @Value("${app.upload.max-temp-bytes-per-user:1073741824}")
  private long maxTempBytes = 1073741824L;

  public MediaService(UploadCoordinator uploads, FileStorage storage) {
    this.uploads = uploads;
    this.storage = storage;
  }

  public UploadSession initialize(
      String user, String upload, String name, long bytes, int total, String folder) {
    return uploads.initialize(user, upload, name, bytes, total, folder);
  }

  public UploadSession initialize(
      String user,
      String upload,
      String name,
      long bytes,
      int total,
      String folder,
      String checksum) {
    return uploads.initialize(user, upload, name, bytes, total, folder, checksum);
  }

  public void saveChunk(MultipartFile chunk, String guid, int index, int total, String user)
      throws IOException {
    validate(total);
    InputRules.uuid(user);
    InputRules.uuid(guid);
    if (index < 0
        || index >= total
        || chunk.getSize() > CHUNK_SIZE
        || (chunk.isEmpty() && total != 1))
      throw DomainException.invalid("Mảnh tệp không hợp lệ hoặc vượt quá 5 MiB");
    try {
      uploads.chunk(
          user,
          guid,
          total,
          session -> {
            try {
              if (session.getExpectedBytes() != null) {
                long expected =
                    index == total - 1
                        ? session.getExpectedBytes() - (total - 1) * CHUNK_SIZE
                        : CHUNK_SIZE;
                if (expected != chunk.getSize())
                  throw DomainException.invalid("Kích thước mảnh không khớp");
              }
              Path directory = directory(user, guid);
              Files.createDirectories(directory);
              long used = temporaryBytes(directory.getParent()),
                  previous =
                      Files.exists(directory.resolve(index + ".tmp"))
                          ? Files.size(directory.resolve(index + ".tmp"))
                          : 0;
              if (chunk.getSize() > maxTempBytes - (used - previous))
                throw new DomainException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "UPLOAD_TEMP_QUOTA",
                    "Vượt giới hạn dữ liệu tạm");
              Path partial = directory.resolve(index + ".part");
              try (InputStream input = chunk.getInputStream()) {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
              } catch (IOException error) {
                Files.deleteIfExists(partial);
                throw error;
              }
              Files.move(
                  partial, directory.resolve(index + ".tmp"), StandardCopyOption.REPLACE_EXISTING);
              Files.setLastModifiedTime(directory, FileTime.from(Instant.now()));
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          });
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
  }

  public MediaFile mergeChunks(String guid, String name, int total, String user, String folder)
      throws IOException {
    validate(total);
    String extension = fileExtension(name);
    UploadCoordinator.Claim claim = uploads.claim(user, guid, name, total, folder);
    if (claim.completed() != null) return claim.completed();
    Path output = null;
    try {
      Path directory = directory(user, guid);
      for (int index = 0; index < total; index++)
        if (!Files.isRegularFile(directory.resolve(index + ".tmp"), LinkOption.NOFOLLOW_LINKS))
          throw DomainException.invalid("Thiếu mảnh thứ " + index);
      output = storage.allocate(claim.token(), extension);
      long bytes = 0;
      java.security.MessageDigest digest;
      try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
      try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
        for (int index = 0; index < total; index++)
          try (InputStream input =
              new java.security.DigestInputStream(
                  Files.newInputStream(directory.resolve(index + ".tmp")), digest)) {
            bytes += input.transferTo(stream);
          }
      }
      MediaFile result =
          new MediaFile(
              claim.session().getResultFileId(),
              InputRules.name(name),
              storage.key(output),
              extension,
              bytes,
              claim.session().getFolderId(),
              user,
              "Ready",
              "Local_Upload");
      result.setChecksum(java.util.HexFormat.of().formatHex(digest.digest()));
      MediaFile saved = uploads.commit(claim, result);
      if (!saved.getStoragePath().equals(result.getStoragePath())) storage.cleanup(output);
      cleanup(user, guid);
      return saved;
    } catch (IOException | RuntimeException error) {
      uploads.discard(claim, output, storage);
      try {
        uploads.failed(claim);
      } catch (RuntimeException ignored) {
      }
      throw error;
    }
  }

  public record StatusView(
      String uploadId,
      String filename,
      long size,
      int totalChunks,
      String folderId,
      String status,
      String resultFileId,
      List<Integer> receivedChunks) {}

  public StatusView status(String user, String guid) throws IOException {
    UploadSession session = uploads.get(user, guid);
    List<Integer> received = new ArrayList<>();
    Path directory = directory(user, guid);
    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      try (var entries = Files.list(directory)) {
        for (Path entry : entries.toList()) {
          String name = entry.getFileName().toString();
          if (name.matches("[0-9]+\\.tmp")
              && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            try {
              int index = Integer.parseInt(name.substring(0, name.length() - 4));
              if (index < session.getTotalChunks()) received.add(index);
            } catch (NumberFormatException ignored) {
            }
          }
        }
      }
    }
    Collections.sort(received);
    return new StatusView(
        guid,
        session.getFilename(),
        session.getExpectedBytes() == null ? -1 : session.getExpectedBytes(),
        session.getTotalChunks(),
        session.getFolderId(),
        session.getStatus().name(),
        session.getStatus() == UploadSession.Status.COMPLETED ? session.getResultFileId() : null,
        received);
  }

  public void abort(String user, String guid) {
    uploads.abort(user, guid);
    cleanup(user, guid);
  }

  public boolean cleanup(String user, String guid) {
    try {
      Path target = directory(user, guid),
          root = Path.of(tempStorageDir).toAbsolutePath().normalize();
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
      if (Files.isSymbolicLink(target)
          || !target.toRealPath().startsWith(root.toRealPath())
          || target.getNameCount() != root.getNameCount() + 2)
        throw new IOException("Unsafe upload cleanup");
      try (var entries = Files.walk(target)) {
        for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(entry);
      }
      return true;
    } catch (IOException error) {
      return false;
    }
  }

  private long temporaryBytes(Path owner) throws IOException {
    if (!Files.isDirectory(owner)) return 0;
    long bytes = 0;
    try (var paths = Files.walk(owner)) {
      for (Path path : paths.toList())
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && (path.toString().endsWith(".tmp") || path.toString().endsWith(".part")))
          bytes += Files.size(path);
    }
    return bytes;
  }

  private Path directory(String user, String guid) {
    InputRules.uuid(user);
    InputRules.uuid(guid);
    return Path.of(tempStorageDir).toAbsolutePath().normalize().resolve(user).resolve(guid);
  }

  private void validate(int total) {
    if (total < 1 || total > 10000) throw DomainException.invalid("Số mảnh không hợp lệ");
  }

  public static String fileExtension(String name) {
    String valid = InputRules.name(name);
    int dot = valid.lastIndexOf('.');
    if (dot <= 0 || dot == valid.length() - 1) return "";
    String extension = valid.substring(dot + 1);
    if (!extension.matches("[a-zA-Z0-9]{1,10}"))
      throw DomainException.invalid("Phần mở rộng không hợp lệ");
    return extension;
  }
}

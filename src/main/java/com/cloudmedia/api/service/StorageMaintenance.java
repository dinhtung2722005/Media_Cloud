package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.UploadSession;
import com.cloudmedia.api.repository.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StorageMaintenance {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(StorageMaintenance.class);
  private final MediaFileRepository files;
  private final MediaJobRepository jobs;
  private final UploadSessionRepository uploads;
  private final FileStorage storage;
  private final FileService fileService;
  private final MediaService media;
  private final AccountTransactions accounts;
  private final Path temporary;
  private final long grace;

  public StorageMaintenance(
      MediaFileRepository files,
      MediaJobRepository jobs,
      UploadSessionRepository uploads,
      FileStorage storage,
      FileService fileService,
      MediaService media,
      AccountTransactions accounts,
      @Value("${app.storage.temp-dir:./storage/temp}") String temporary,
      @Value("${app.storage.orphan-grace-seconds:86400}") long grace) {
    if (grace < 3600) throw new IllegalArgumentException("Orphan grace must be at least one hour");
    this.files = files;
    this.jobs = jobs;
    this.uploads = uploads;
    this.storage = storage;
    this.fileService = fileService;
    this.media = media;
    this.accounts = accounts;
    this.temporary = Path.of(temporary).toAbsolutePath().normalize();
    this.grace = grace;
  }

  @Scheduled(fixedDelayString = "${app.storage.reconcile-ms:60000}")
  public void reconcile() {
    try {
      for (var file : files.findByStatus("Deleting", PageRequest.of(0, 100)))
        fileService.retryDeletion(file);
      for (var candidate :
          uploads.cleanupCandidates(
              List.of(UploadSession.Status.COMPLETED, UploadSession.Status.ABORTED),
              Instant.now(),
              PageRequest.of(0, 100)))
        accounts.locked(
            candidate.getUserId(),
            () -> {
              UploadSession session = uploads.findById(candidate.getId()).orElse(null);
              if (session == null) return;
              if (session.getStatus() != UploadSession.Status.COMPLETED
                  && session.getStatus() != UploadSession.Status.ABORTED) {
                if (session.getExpiresAt().isAfter(Instant.now())
                    || (session.getLeaseUntil() != null
                        && session.getLeaseUntil().isAfter(Instant.now()))) return;
                session.abort();
              }
              if (media.cleanup(session.getUserId(), session.getUploadId())) session.cleaned();
              uploads.saveAndFlush(session);
            });
    } catch (RuntimeException unavailable) {
      log.warn("Đối soát storage sẽ thử lại ({})", unavailable.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${app.upload.cleanup-interval-ms:3600000}")
  public void removeOrphans() {
    try {
      Instant cutoff = Instant.now().minusSeconds(grace);
      try (var entries = Files.list(storage.objects())) {
        for (Path directory : entries.toList()) {
          if (!oldUuidDirectory(directory, cutoff)) continue;
          String token = directory.getFileName().toString(), prefix = storage.key(directory) + "/";
          if (jobs.existsByLeaseTokenAndLeaseUntilAfter(token, Instant.now())
              || uploads.existsByLeaseTokenAndLeaseUntilAfter(token, Instant.now())) continue;
          if (files.existsByStoragePathStartingWith(prefix)
              || files.existsByStoragePathStartingWith(
                  directory.toString() + java.io.File.separator)) continue;
          storage.cleanupAttempt(directory);
        }
      }
      if (Files.isDirectory(temporary, LinkOption.NOFOLLOW_LINKS))
        try (var owners = Files.list(temporary)) {
          for (Path owner : owners.toList()) {
            if (!uuidDirectory(owner)) continue;
            try (var entries = Files.list(owner)) {
              for (Path directory : entries.toList())
                if (oldUuidDirectory(directory, cutoff)) {
                  String user = owner.getFileName().toString(),
                      upload = directory.getFileName().toString();
                  // Registered uploads are handled transactionally above; this only removes
                  // pre-session leftovers.
                  if (!uploads.existsById(UploadSession.key(user, upload)))
                    media.cleanup(user, upload);
                }
            }
          }
        }
    } catch (IOException | RuntimeException unavailable) {
      log.warn("Dọn storage sẽ thử lại ({})", unavailable.getClass().getSimpleName());
    }
  }

  private boolean oldUuidDirectory(Path path, Instant cutoff) throws IOException {
    return uuidDirectory(path)
        && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
  }

  private boolean uuidDirectory(Path path) {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
      return false;
    try {
      return UUID.fromString(path.getFileName().toString())
          .toString()
          .equals(path.getFileName().toString());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}

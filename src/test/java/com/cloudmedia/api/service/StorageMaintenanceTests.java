package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;

import com.cloudmedia.api.entity.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StorageMaintenanceTests extends BusinessTestBase {
  StorageMaintenance maintenance() {
    return new StorageMaintenance(
        files,
        jobRows,
        sessions,
        storage,
        fileService,
        media,
        accounts,
        root.resolve("temp").toString(),
        3600);
  }

  @Test
  void expiredUploadsReleaseReservationAndRemoveChunks() throws Exception {
    String guid = UUID.randomUUID().toString();
    media.initialize(user, guid, "file.txt", 3, 1, null);
    media.saveChunk(
        new org.springframework.mock.web.MockMultipartFile("chunk", new byte[] {1, 2, 3}),
        guid,
        0,
        1,
        user);
    accounts.locked(
        user,
        () -> {
          var session = sessions.findById(UploadSession.key(user, guid)).orElseThrow();
          session.touch(Instant.now().minusSeconds(1));
          sessions.saveAndFlush(session);
        });
    maintenance().reconcile();
    var session = sessions.findById(UploadSession.key(user, guid)).orElseThrow();
    assertThat(session.getStatus()).isEqualTo(UploadSession.Status.ABORTED);
    assertThat(session.getReservedBytes()).isZero();
    assertThat(session.isCleaned()).isTrue();
    assertThat(media.status(user, guid).receivedChunks()).isEmpty();
  }

  @Test
  void orphanCleanupPreservesCommittedAndLeasedOutputs() throws Exception {
    var committed = file("committed.mp4", null);
    Path committedDir = storage.resolve(committed.getStoragePath()).getParent();
    var running = jobs.claim(jobs.enqueueYouTube(user, "url", null)).orElseThrow();
    Path live = storage.allocate(running.token(), "mp4");
    Files.writeString(live, "live");
    Path orphan = storage.allocate(UUID.randomUUID().toString(), "mp4");
    Files.writeString(orphan, "orphan");
    FileTime old = FileTime.from(Instant.now().minusSeconds(7200));
    for (Path path : List.of(committedDir, live.getParent(), orphan.getParent()))
      Files.setLastModifiedTime(path, old);
    maintenance().removeOrphans();
    assertThat(orphan).doesNotExist();
    assertThat(live).exists();
    assertThat(storage.resolve(committed.getStoragePath())).exists();
  }

  @Test
  void storageRejectsPathsOutsideConfiguredVolume() throws Exception {
    Path outside = root.resolve("outside.txt");
    Files.writeString(outside, "private");
    assertThatThrownBy(() -> storage.resolve(outside.toString()))
        .isInstanceOf(com.cloudmedia.api.domain.DomainException.class);
    assertThatThrownBy(() -> storage.resolve("../../outside.txt"))
        .isInstanceOf(com.cloudmedia.api.domain.DomainException.class);
    assertThat(outside).exists();
  }
}

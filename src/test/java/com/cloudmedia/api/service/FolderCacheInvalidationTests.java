package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FolderCacheInvalidationTests extends BusinessTestBase {
  @Test
  void createRenameDeleteInvalidateOwner() {
    var folder = folderService.createFolder("New", null, user);
    folderService.updateFolderName(folder.getId(), "Renamed", user);
    folderService.deleteFolder(folder.getId(), user);
    verify(cache, times(3)).invalidateAfterCommit(user);
  }

  @Test
  void busyFolderCannotBeDeletedUntilPendingJobIsCancelled() {
    var folder = folderService.createFolder("Work", null, user);
    String job = jobs.enqueueYouTube(user, "url", folder.getId());
    assertThatThrownBy(() -> folderService.deleteFolder(folder.getId(), user))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo("FOLDER_BUSY"));
    jobs.cancel(job, user);
    folderService.deleteFolder(folder.getId(), user);
  }

  @Test
  void openUploadBlocksDeletionAndFinalizationRechecksFolder() {
    var folder = folderService.createFolder("Work", null, user);
    String id = UUID.randomUUID().toString();
    media.initialize(user, id, "a.txt", 0, 1, folder.getId());
    assertThatThrownBy(() -> folderService.deleteFolder(folder.getId(), user))
        .isInstanceOf(DomainException.class);
    media.abort(user, id);
    folderService.deleteFolder(folder.getId(), user);
  }

  @Test
  void invalidNamesAndForeignFoldersNeverReachCache() {
    assertThatThrownBy(() -> folderService.createFolder("   ", null, user))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> folderService.createFolder("valid", "missing", user))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> folderService.updateFolderName("missing", "name", user))
        .isInstanceOf(DomainException.class);
    verifyNoInteractions(cache);
  }

  @Test
  void foreignKeyPreventsOrphanedMetadataEvenOutsideService() throws Exception {
    var folder = folderService.createFolder("Parent", null, user);
    file("file.txt", folder.getId());
    assertThatThrownBy(() -> folders.deleteById(folder.getId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}

package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.FileShare;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FileShareServiceTests extends BusinessTestBase {
  String token(FileShareService.Created created) {
    return created.path().substring(3);
  }

  @Test
  void createsAnExpiringOpaqueLinkAndPersistsOnlyTheHash() throws Exception {
    var file = file("video.mp4", null);
    Instant before = Instant.now();
    var created = fileShares.create(file.getId(), user, null);
    assertThat(token(created)).matches("[A-Za-z0-9_-]{43}");
    assertThat(created.expiresAt())
        .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    FileShare stored = shareRows.findById(created.id()).orElseThrow();
    assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(token(created));
    var publicFile = fileShares.metadata(token(created));
    assertThat(publicFile.title()).isEqualTo("video.mp4");
    assertThat(publicFile.fileSize()).isEqualTo(4);
    assertThat(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(publicFile))
        .doesNotContain("storagePath", "userId", "folderId");
  }

  @Test
  void foreignUsersCannotCreateListOrRevokeLinks() throws Exception {
    var file = file("a.txt", null);
    var link = fileShares.create(file.getId(), user, 1L);
    String other = UUID.randomUUID().toString();
    users.saveAndFlush(
        new com.cloudmedia.api.entity.User(other, other, null, "hash", LocalDateTime.now()));
    assertThatThrownBy(() -> fileShares.create(file.getId(), other, 1L))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> fileShares.list(file.getId(), other))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> fileShares.revoke(file.getId(), link.id(), other))
        .isInstanceOf(DomainException.class);
    assertThat(fileShares.metadata(token(link)).title()).isEqualTo("a.txt");
  }

  @Test
  void revokeIsIdempotentAndBlocksAllNewAccess() throws Exception {
    var file = file("a.txt", null);
    var created = fileShares.create(file.getId(), user, 1L);
    fileShares.revoke(file.getId(), created.id(), user);
    fileShares.revoke(file.getId(), created.id(), user);
    assertThatThrownBy(() -> fileShares.resource(token(created)))
        .isInstanceOf(DomainException.class);
    assertThat(fileShares.list(file.getId(), user).get(0).status()).isEqualTo("REVOKED");
  }

  @Test
  void expirationMalformedTokensAndDeletingFilesCannotBeRead() throws Exception {
    var file = file("a.txt", null);
    var expired = fileShares.create(file.getId(), user, 1L);
    var row = shareRows.findById(expired.id()).orElseThrow();
    ReflectionTestUtils.setField(row, "expiresAt", Instant.now().minusSeconds(1));
    shareRows.saveAndFlush(row);
    assertThatThrownBy(() -> fileShares.metadata(token(expired)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> fileShares.access("../escape")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> fileShares.access("a".repeat(43))).isInstanceOf(DomainException.class);
    var link = fileShares.create(file.getId(), user, 1L);
    file.setStatus("Deleting");
    files.saveAndFlush(file);
    assertThatThrownBy(() -> fileShares.resource(token(link))).isInstanceOf(DomainException.class);
  }

  @Test
  void deletingFileRemovesItsShareRows() throws Exception {
    var file = file("a.txt", null);
    var created = fileShares.create(file.getId(), user, 1L);
    fileService.deleteFile(file.getId(), user);
    assertThat(shareRows.existsById(created.id())).isFalse();
    assertThatThrownBy(() -> fileShares.access(token(created))).isInstanceOf(DomainException.class);
  }

  @Test
  void validatesLifetimeAndCapsActiveLinks() throws Exception {
    var file = file("a.txt", null);
    assertThatThrownBy(() -> fileShares.create(file.getId(), user, 0L))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> fileShares.create(file.getId(), user, 721L))
        .isInstanceOf(DomainException.class);
    List<FileShareService.Created> links = new ArrayList<>();
    for (int i = 0; i < 10; i++) links.add(fileShares.create(file.getId(), user, 1L));
    assertThatThrownBy(() -> fileShares.create(file.getId(), user, 1L))
        .isInstanceOf(DomainException.class);
    fileShares.revoke(file.getId(), links.get(0).id(), user);
    assertThat(fileShares.create(file.getId(), user, 1L)).isNotNull();
  }

  @Test
  void activeLinksRemainVisibleWhenOldHistoryIsLarge() throws Exception {
    var file = file("a.txt", null);
    var active = fileShares.create(file.getId(), user, 24L);
    for (int i = 0; i < 55; i++)
      shareRows.saveAndFlush(
          new FileShare(
              UUID.randomUUID().toString(),
              user,
              file.getId(),
              UUID.randomUUID().toString(),
              Instant.now().plusSeconds(i),
              Instant.now().minusSeconds(1)));
    var listed = fileShares.list(file.getId(), user);
    assertThat(listed).hasSize(50);
    assertThat(listed.get(0).id()).isEqualTo(active.id());
  }
}

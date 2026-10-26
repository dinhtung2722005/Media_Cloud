package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;

import com.cloudmedia.api.domain.DomainException;
import java.util.*;
import org.junit.jupiter.api.Test;

class DriveListingServiceTests extends BusinessTestBase {
  @Test
  void paginatesAndSearchesVietnameseWithoutExposingStorage() throws Exception {
    file("Bài hát.mp3", null);
    file("bai giang.mp3", null);
    file("video.mp4", null);
    var first = listings.list(user, null, 0, 1, "bai", "name", "audio");
    assertThat(first.get("totalFiles")).isEqualTo(2L);
    assertThat(first.get("hasMore")).isEqualTo(true);
    var next = listings.list(user, null, 1, 1, "bai", "name", "audio");
    assertThat(next.get("hasMore")).isEqualTo(false);
    assertThat(first.get("files")).isNotEqualTo(next.get("files"));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first);
    assertThat(json).doesNotContain("storagePath").doesNotContain("userId");
  }

  @Test
  void deletionAndOwnershipAreCheckedBeforeCache() throws Exception {
    var folder = folderService.createFolder("Private", null, user);
    file("child.txt", folder.getId());
    assertThatThrownBy(
            () ->
                listings.list(
                    UUID.randomUUID().toString(), folder.getId(), 0, 10, "", "name", "all"))
        .isInstanceOf(DomainException.class);
    assertThat(((List<?>) listings.list(user, null, 0, 10, "", "name", "all").get("files")))
        .isEmpty();
    assertThat(
            ((List<?>) listings.list(user, folder.getId(), 0, 10, "", "name", "all").get("files")))
        .hasSize(1);
  }

  @Test
  void validatesPaginationAndTreatsSearchWildcardsLiterally() throws Exception {
    file("regular.txt", null);
    file("100%.txt", null);
    assertThat(((List<?>) listings.list(user, null, 0, 50, "%", "name", "all").get("files")))
        .hasSize(1);
    assertThatThrownBy(() -> listings.list(user, null, -1, 50, "", "name", "all"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> listings.list(user, null, 0, 500, "", "name", "all"))
        .isInstanceOf(DomainException.class);
  }
}

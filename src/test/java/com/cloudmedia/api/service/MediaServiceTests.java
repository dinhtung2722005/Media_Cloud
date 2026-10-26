package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;

import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class MediaServiceTests extends BusinessTestBase {
  String guid() {
    return UUID.randomUUID().toString();
  }

  void chunk(String id, int index, int total, String content) throws Exception {
    media.saveChunk(
        new MockMultipartFile("chunk", content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        id,
        index,
        total,
        user);
  }

  @Test
  void chunksCanBeReplacedAndCompletionAlwaysReturnsTheSameFile() throws Exception {
    String id = guid();
    chunk(id, 1, 2, "world");
    chunk(id, 0, 2, "wrong");
    chunk(id, 0, 2, "hello ");
    assertThat(media.status(user, id).receivedChunks()).containsExactly(0, 1);
    MediaFile first = media.mergeChunks(id, "video.mp4", 2, user, null),
        again = media.mergeChunks(id, "video.mp4", 2, user, null);
    assertThat(again.getId()).isEqualTo(first.getId());
    assertThat(Files.readString(storage.resolve(first.getStoragePath()))).isEqualTo("hello world");
    assertThat(media.status(user, id).status()).isEqualTo("COMPLETED");
    assertThat(media.status(user, id).receivedChunks()).isEmpty();
  }

  @Test
  void missingChunksReopenSessionAndPreserveReceivedData() throws Exception {
    String id = guid();
    chunk(id, 0, 2, "hello ");
    assertThatThrownBy(() -> media.mergeChunks(id, "video.mp4", 2, user, null))
        .isInstanceOf(DomainException.class);
    assertThat(media.status(user, id).receivedChunks()).containsExactly(0);
    assertThat(media.status(user, id).status()).isEqualTo("OPEN");
    chunk(id, 1, 2, "world");
    assertThat(media.mergeChunks(id, "video.mp4", 2, user, null)).isNotNull();
  }

  @Test
  void declaredSizeAndChecksumAreVerifiedBeforeMetadataCommit() throws Exception {
    String id = guid();
    media.initialize(user, id, "hello.txt", 5, 1, null, "0".repeat(64));
    chunk(id, 0, 1, "hello");
    assertThatThrownBy(() -> media.mergeChunks(id, "hello.txt", 1, user, null))
        .isInstanceOf(DomainException.class);
    assertThat(files.existsById(uploads.get(user, id).getResultFileId())).isFalse();
    assertThat(media.status(user, id).receivedChunks()).containsExactly(0);
    String valid = guid();
    String sha =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest("hello".getBytes()));
    media.initialize(user, valid, "hello.txt", 5, 1, null, sha);
    chunk(valid, 0, 1, "hello");
    assertThat(media.mergeChunks(valid, "hello.txt", 1, user, null).getChecksum()).isEqualTo(sha);
  }

  @Test
  void initializedUploadsRejectWrongChunkSizeAndChangedMetadata() throws Exception {
    String id = guid();
    media.initialize(user, id, "file.txt", 5, 1, null);
    assertThatThrownBy(() -> chunk(id, 0, 1, "wrong-size")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> media.initialize(user, id, "other.txt", 5, 1, null))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> media.status(UUID.randomUUID().toString(), id))
        .isInstanceOf(DomainException.class);
    media.abort(user, id);
    assertThatThrownBy(() -> chunk(id, 0, 1, "hello")).isInstanceOf(DomainException.class);
    assertThat(sessions.findById(UploadSession.key(user, id)).orElseThrow().getReservedBytes())
        .isZero();
  }

  @Test
  void onlyOneCompletionLeaseCanBeUsed() throws Exception {
    String id = guid();
    media.initialize(user, id, "file.txt", 0, 1, null);
    var claim = uploads.claim(user, id, "file.txt", 1, null);
    assertThatThrownBy(() -> uploads.claim(user, id, "file.txt", 1, null))
        .isInstanceOf(DomainException.class);
    uploads.failed(claim);
    assertThat(uploads.claim(user, id, "file.txt", 1, null).token()).isNotEqualTo(claim.token());
  }

  @Test
  void invalidPathsOversizeAndInvalidChunkNumbersAreRejected() {
    assertThatThrownBy(
            () ->
                media.saveChunk(
                    new MockMultipartFile("chunk", new byte[5242881]), guid(), 0, 1, user))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> chunk("../escape", 0, 1, "x")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> chunk(guid(), -1, 1, "x")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> media.initialize(user, guid(), "../escape.txt", 1, 1, null))
        .isInstanceOf(DomainException.class);
  }
}

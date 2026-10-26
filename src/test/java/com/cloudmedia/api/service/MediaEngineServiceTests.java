package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.FolderRepository;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MediaEngineServiceTests {
  @TempDir Path directory;
  FileService files;
  MediaProcessRunner processes;
  FileStorage storage;
  MediaEngineService engine;

  @BeforeEach
  void setup() {
    files = mock(FileService.class);
    processes = mock(MediaProcessRunner.class);
    storage = new FileStorage(directory.toString());
    engine = new MediaEngineService(files, mock(FolderRepository.class), processes, storage);
  }

  MediaJob job() {
    return new MediaJob(
        UUID.randomUUID().toString(),
        "owner",
        MediaJob.Type.YOUTUBE,
        null,
        "https://youtu.be/example",
        null);
  }

  @Test
  void preparesAnIsolatedOutputWithoutPublishingMetadata() throws Exception {
    List<Integer> progress = new ArrayList<>();
    when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class)))
        .thenAnswer(
            call -> {
              List<String> cmd = call.getArgument(0);
              assertThat(cmd).contains("--no-playlist", "--max-filesize");
              assertThat(cmd.get(cmd.size() - 2)).isEqualTo("--");
              ((Consumer<String>) call.getArgument(2)).accept("[download] 25.0%");
              Files.writeString(Path.of(cmd.get(cmd.indexOf("-o") + 1)), "video");
              return 0;
            });
    String token = UUID.randomUUID().toString();
    var prepared = engine.prepare(job(), token, () -> false, progress::add);
    assertThat(progress).containsExactly(25);
    assertThat(prepared.output().getParent().getFileName().toString()).isEqualTo(token);
    assertThat(prepared.file().getStoragePath()).startsWith("objects/");
    verifyNoInteractions(files);
    prepared.close();
    assertThat(prepared.output()).doesNotExist();
  }

  @Test
  void cleansOnlyItsOwnAttemptAfterToolFailure() throws Exception {
    Path other = storage.allocate(UUID.randomUUID().toString(), "mp4");
    Files.writeString(other, "keep");
    when(processes.run(anyList(), any(), any(), any()))
        .thenThrow(new java.io.IOException("timeout"));
    String token = UUID.randomUUID().toString();
    assertThatThrownBy(() -> engine.prepare(job(), token, () -> false, value -> {}))
        .isInstanceOf(IllegalStateException.class);
    assertThat(other).exists();
    assertThat(storage.objects().resolve(token)).doesNotExist();
  }

  @Test
  void cancellationAfterToolExitRemovesStaging() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean stop =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(processes.run(anyList(), any(), any(), any()))
        .thenAnswer(
            call -> {
              stop.set(true);
              return 0;
            });
    String token = UUID.randomUUID().toString();
    assertThatThrownBy(() -> engine.prepare(job(), token, stop::get, value -> {}))
        .isInstanceOf(CancellationException.class);
    assertThat(storage.objects().resolve(token)).doesNotExist();
  }

  @Test
  void conversionUsesFreshOwnershipAndConfinedPaths() throws Exception {
    Path source = directory.resolve("source.mp4");
    Files.writeString(source, "source");
    when(files.requireOwnedFile("source", "owner"))
        .thenReturn(
            new MediaFile(
                "source",
                "name.mp4",
                source.toString(),
                "mp4",
                6L,
                null,
                "owner",
                "Ready",
                "Local"));
    when(processes.run(anyList(), any(), any(), any()))
        .thenAnswer(
            call -> {
              List<String> cmd = call.getArgument(0);
              assertThat(cmd).contains("-nostdin", "-n", "-protocol_whitelist", "file,pipe");
              Files.writeString(Path.of(cmd.get(cmd.size() - 1)), "mp3");
              return 0;
            });
    var prepared =
        engine.prepare(
            new MediaJob(
                UUID.randomUUID().toString(),
                "owner",
                MediaJob.Type.CONVERT_MP3,
                "source",
                null,
                null),
            UUID.randomUUID().toString(),
            () -> false,
            v -> {});
    assertThat(prepared.file().getTitle()).isEqualTo("name.mp3");
    prepared.close();
  }
}

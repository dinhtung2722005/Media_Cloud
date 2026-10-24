package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaEngineServiceTests {
    @TempDir Path directory;
    MediaFileRepository files;
    FolderRepository folders;
    FileService ownedFiles;
    SimpMessagingTemplate messaging;
    MediaProcessRunner processes;
    MediaEngineService engine;
    Path storage;

    @BeforeEach void setup() throws Exception {
        files = mock(MediaFileRepository.class);
        folders = mock(FolderRepository.class);
        ownedFiles = mock(FileService.class);
        messaging = mock(SimpMessagingTemplate.class);
        processes = mock(MediaProcessRunner.class);
        engine = new MediaEngineService(files, folders, messaging, ownedFiles, processes);
        storage = directory.resolve("storage");
        Files.createDirectories(storage);
        ReflectionTestUtils.setField(engine, "baseStorageDir", storage.toString());
        ReflectionTestUtils.setField(engine, "ffmpegPath", "configured-ffmpeg");
        ReflectionTestUtils.setField(engine, "ytDlpPath", "configured-yt-dlp");
    }

    @Test void sendsProgressAndCompletionOnlyToOwner() throws Exception {
        when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            assertThat(command.get(0)).isEqualTo("configured-yt-dlp");
            assertThat(command).contains("--no-playlist", "--max-filesize");
            assertThat(command.get(command.size() - 2)).isEqualTo("--");
            Consumer<String> progress = invocation.getArgument(2);
            progress.accept("[download] 25.0%");
            Files.writeString(Path.of(command.get(command.indexOf("-o") + 1)), "video");
            return 0;
        });
        engine.downloadYouTubeVideo("https://youtu.be/example", "owner", null);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(messaging, times(2)).convertAndSendToUser(eq("owner"), eq("/queue/progress"), payloads.capture());
        assertThat(((Map<?, ?>) payloads.getAllValues().get(0)).get("status")).isEqualTo("PROCESSING");
        assertThat(((Map<?, ?>) payloads.getAllValues().get(1)).get("status")).isEqualTo("COMPLETED");
        verifyNoMoreInteractions(messaging);
        ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
        verify(files).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("owner");
    }

    @Test void reportsToolFailureToOwnerAndDoesNotSaveMetadata() throws Exception {
        when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class))).thenThrow(new IOException("Timeout"));
        assertThatThrownBy(() -> engine.downloadYouTubeVideo("https://youtu.be/example", "owner", null))
                .isInstanceOf(IllegalStateException.class);
        verify(messaging).convertAndSendToUser(eq("owner"), eq("/queue/progress"),
                argThat((Object value) -> "FAILED".equals(((Map<?, ?>) value).get("status"))));
        verify(files, never()).saveAndFlush(any());
    }

    @Test void convertsOwnedFileWithGeneratedOutputAndSavesMetadata() throws Exception {
        Path input = storage.resolve("source.mp4");
        Files.writeString(input, "source");
        MediaFile source = new MediaFile("source-id", "Source.mp4", input.toString(), "mp4", 6L, null, "owner", "Ready", "Local");
        when(ownedFiles.requireOwnedFile("source-id", "owner")).thenReturn(source);
        when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            assertThat(command).contains("-nostdin", "-n", "-protocol_whitelist", "file,pipe");
            assertThat(command.get(command.indexOf("-i") + 1)).isEqualTo(input.toRealPath().toString());
            Path output = Path.of(command.get(command.size() - 1));
            assertThat(output.getParent()).isEqualTo(storage.toRealPath());
            assertThat(output.getFileName().toString()).matches("[0-9a-f-]{36}\\.mp3");
            Files.writeString(output, "audio");
            return 0;
        });
        engine.convertToMp3("source-id", "owner");
        ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
        verify(files).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Source.mp3");
        assertThat(saved.getValue().getUserId()).isEqualTo("owner");
        assertThat(saved.getValue().getFileSize()).isEqualTo(5);
        assertThat(input).exists();
    }

    @Test void refusesInputOutsideStorage() throws Exception {
        Path outside = directory.resolve("outside.mp4");
        Files.writeString(outside, "media");
        when(ownedFiles.requireOwnedFile("source-id", "owner")).thenReturn(
                new MediaFile("source-id", "Outside.mp4", outside.toString(), "mp4", 5L, null, "owner", "Ready", "Local"));
        assertThatThrownBy(() -> engine.convertToMp3("source-id", "owner")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(processes);
        verify(files, never()).saveAndFlush(any());
    }

    @Test void failureRemovesPartialOutput() throws Exception {
        when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Files.writeString(Path.of(command.get(command.indexOf("-o") + 1) + ".part"), "partial");
            throw new IOException("Download failed");
        });
        assertThatThrownBy(() -> engine.downloadYouTubeVideo("https://youtu.be/example", "owner", null))
                .isInstanceOf(IllegalStateException.class);
        try (var contents = Files.list(storage)) { assertThat(contents.count()).isZero(); }
    }

    @Test void cancellationPreservesInterruptFlag() throws Exception {
        when(processes.run(anyList(), any(Duration.class), any(), any(BooleanSupplier.class))).thenThrow(new InterruptedException("Cancelled"));
        try {
            assertThatThrownBy(() -> engine.downloadYouTubeVideo("https://youtu.be/example", "owner", null))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void redeliveredCompletedJobDoesNotGenerateAnotherFile() throws Exception {
        String jobId = UUID.randomUUID().toString();
        MediaFile output = new MediaFile();
        output.setUserId("owner");
        when(files.findById(jobId)).thenReturn(Optional.of(output));
        engine.convertToMp3("source-id", "owner", jobId, () -> false);
        verifyNoInteractions(processes, ownedFiles);
        verify(files, never()).saveAndFlush(any());
    }
}

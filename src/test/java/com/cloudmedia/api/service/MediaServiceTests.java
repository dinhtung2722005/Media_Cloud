package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.Folder;
import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.repository.MediaFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MediaServiceTests {
    @TempDir Path directory;
    private MediaFileRepository files;
    private FolderRepository folders;
    private MediaService service;
    private final String userId = UUID.randomUUID().toString();
    private final String fileGuid = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        files = mock(MediaFileRepository.class);
        folders = mock(FolderRepository.class);
        service = new MediaService(files, folders);
        ReflectionTestUtils.setField(service, "tempStorageDir", directory.resolve("temp").toString());
        ReflectionTestUtils.setField(service, "baseStorageDir", directory.resolve("media").toString());
        when(files.saveAndFlush(any(MediaFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void assemblesInOrderAndAllowsResendingAChunk() throws Exception {
        save(1, 2, "world");
        save(0, 2, "wrong");
        save(0, 2, "hello ");
        MediaFile file = service.mergeChunks(fileGuid, "video.mp4", 2, userId, null);
        assertThat(Files.readString(Path.of(file.getStoragePath()))).isEqualTo("hello world");
        assertThat(file.getFileSize()).isEqualTo(11);
        assertThat(file.getUserId()).isEqualTo(userId);
        assertThat(file.getExtension()).isEqualTo("mp4");
        assertThat(uploadDir()).doesNotExist();
    }

    @Test
    void missingChunksArePreservedForRetry() throws Exception {
        save(0, 2, "hello ");
        assertThatThrownBy(() -> service.mergeChunks(fileGuid, "video.mp4", 2, userId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(uploadDir().resolve("0.tmp")).exists();
        verifyNoInteractions(files);
        save(1, 2, "world");
        assertThat(service.mergeChunks(fileGuid, "video.mp4", 2, userId, null)).isNotNull();
    }

    @Test
    void databaseFailureRemovesOutputButPreservesChunks() throws Exception {
        save(0, 1, "content");
        when(files.saveAndFlush(any())).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> service.mergeChunks(fileGuid, "file.txt", 1, userId, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(uploadDir().resolve("0.tmp")).exists();
        try (var outputs = Files.list(directory.resolve("media"))) {
            assertThat(outputs.count()).isZero();
        }
    }

    @Test
    void usersCannotMergeEachOthersChunksOrWriteToTheirFolders() throws Exception {
        save(0, 1, "private");
        assertThatThrownBy(() -> service.mergeChunks(fileGuid, "file.txt", 1, UUID.randomUUID().toString(), null))
                .isInstanceOf(IllegalArgumentException.class);
        Folder folder = new Folder();
        folder.setUserId("other-user");
        when(folders.findById("foreign-folder")).thenReturn(Optional.of(folder));
        assertThatThrownBy(() -> service.mergeChunks(fileGuid, "file.txt", 1, userId, "foreign-folder"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(uploadDir().resolve("0.tmp")).exists();
        verifyNoInteractions(files);
    }

    @Test
    void rejectsTraversalOversizedChunksAndConflictingCounts() throws Exception {
        var chunk = new MockMultipartFile("file", new byte[]{1});
        assertThatThrownBy(() -> service.saveChunk(chunk, "../outside", 0, 1, userId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveChunk(chunk, fileGuid, -1, 1, userId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveChunk(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1]), fileGuid, 0, 1, userId))
                .isInstanceOf(IllegalArgumentException.class);
        save(0, 1, "content");
        assertThatThrownBy(() -> service.saveChunk(chunk, fileGuid, 1, 2, userId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mergeChunks(fileGuid, "../outside.txt", 1, userId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsEmptyFilesWithoutAnExtension() throws Exception {
        save(0, 1, "");
        MediaFile file = service.mergeChunks(fileGuid, "LICENSE", 1, userId, "");
        assertThat(file.getExtension()).isEmpty();
        assertThat(file.getFolderId()).isNull();
        assertThat(file.getFileSize()).isZero();
        assertThat(Path.of(file.getStoragePath())).exists();
    }

    private void save(int index, int total, String contents) throws Exception {
        service.saveChunk(new MockMultipartFile("file", contents.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                fileGuid, index, total, userId);
    }

    private Path uploadDir() {
        return directory.resolve("temp").resolve(userId).resolve(fileGuid);
    }

    @Test
    void temporaryQuotaCountsAllUploadsAndAllowsReplacingSameChunk() throws Exception {
        ReflectionTestUtils.setField(service, "maxTempBytes", 6L);
        save(0, 1, "123456");
        save(0, 1, "123456");
        assertThatThrownBy(() -> service.saveChunk(new MockMultipartFile("file", new byte[]{1}),
                UUID.randomUUID().toString(), 0, 1, userId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(uploadDir().resolve("0.tmp")).exists();
    }

    @Test
    void limitsNumberOfActiveUploadsPerUser() throws Exception {
        ReflectionTestUtils.setField(service, "maxActiveUploads", 1);
        save(0, 1, "first");
        assertThatThrownBy(() -> service.saveChunk(new MockMultipartFile("file", new byte[]{1}),
                UUID.randomUUID().toString(), 0, 1, userId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        service.saveChunk(new MockMultipartFile("file", new byte[]{1}), UUID.randomUUID().toString(), 0, 1,
                UUID.randomUUID().toString());
    }

    @Test
    void cleanupRemovesExpiredUploadsAndKeepsRecentOrUnrelatedDirectories() throws Exception {
        save(0, 1, "stale");
        Files.setLastModifiedTime(uploadDir(), java.nio.file.attribute.FileTime.fromMillis(0));
        String recentGuid = UUID.randomUUID().toString();
        service.saveChunk(new MockMultipartFile("file", new byte[]{1}), recentGuid, 0, 1, userId);
        Path unrelated = directory.resolve("temp").resolve("do-not-delete");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("keep"), "keep");
        service.cleanupExpiredUploads();
        assertThat(uploadDir()).doesNotExist();
        assertThat(directory.resolve("temp").resolve(userId).resolve(recentGuid)).exists();
        assertThat(unrelated.resolve("keep")).exists();
    }
}

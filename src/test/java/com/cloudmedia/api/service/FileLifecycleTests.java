package com.cloudmedia.api.service;

import com.cloudmedia.api.controller.FileController;
import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileLifecycleTests {
    @TempDir Path directory;
    MediaFileRepository files;
    FileService service;
    MediaFile file;
    MockMvc mvc;
    Path physical;

    @BeforeEach void setup() throws Exception {
        files = mock(MediaFileRepository.class);
        service = new FileService(files, mock(FolderRepository.class));
        ReflectionTestUtils.setField(service, "baseStorageDir", directory.toString());
        physical = directory.resolve("video.mp4");
        Files.write(physical, new byte[2 * 1024 * 1024]);
        file = new MediaFile("file", "video.mp4", physical.toString(), "mp4", Files.size(physical), null, "owner", "Ready", "Local");
        when(files.findById("file")).thenReturn(Optional.of(file));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner", null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new FileController(service)).build();
    }

    @AfterEach void cleanupContext() { SecurityContextHolder.clearContext(); }

    @Test void noRangeReturnsWholeFileAndRangeReturnsRequestedBytes() throws Exception {
        var full = mvc.perform(get("/api/files/stream/file")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(full.getContentAsByteArray()).hasSize(2 * 1024 * 1024);
        var partial = mvc.perform(get("/api/files/stream/file").header("Range", "bytes=100-199"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 100-199/2097152"))
                .andReturn().getResponse();
        assertThat(partial.getContentAsByteArray()).hasSize(100);
    }

    @Test void invalidRangeReturns416AndEmptyFileReturns200() throws Exception {
        mvc.perform(get("/api/files/stream/file").header("Range", "bytes=99999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */2097152"));
        Files.write(physical, new byte[0]);
        mvc.perform(get("/api/files/stream/file")).andExpect(status().isOk());
    }

    @Test void failedDiskDeletionKeepsMetadataAndReturnsError() throws Exception {
        Path nonempty = directory.resolve("locked");
        Files.createDirectories(nonempty);
        Files.writeString(nonempty.resolve("content"), "keep");
        file.setStoragePath(nonempty.toString());
        mvc.perform(delete("/api/files/file")).andExpect(status().isInternalServerError());
        verify(files, never()).delete(any(MediaFile.class));
        assertThat(nonempty.resolve("content")).exists();
    }

    @Test void successfulDeleteIsRetryableWhenDatabaseDeletionFails() {
        doThrow(new IllegalStateException("Database unavailable")).doNothing().when(files).delete(file);
        assertThatThrownBy(() -> service.deleteFile("file", "owner")).isInstanceOf(IllegalStateException.class);
        assertThat(physical).doesNotExist();
        service.deleteFile("file", "owner");
        verify(files, times(2)).delete(file);
    }

    @Test void uploadDatabaseFailureRemovesNewPhysicalFile() throws Exception {
        when(files.saveAndFlush(any())).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> service.uploadFile(new MockMultipartFile("file", "new.txt", "text/plain", new byte[]{1}), null, "owner"))
                .isInstanceOf(IllegalStateException.class);
        try (var contents = Files.list(directory)) { assertThat(contents.toList()).containsExactly(physical); }
    }
}

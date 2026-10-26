package com.cloudmedia.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmedia.api.controller.*;
import com.cloudmedia.api.domain.DomainException;
import com.cloudmedia.api.entity.MediaFile;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FileLifecycleTests extends BusinessTestBase {
  MediaFile file;
  MockMvc mvc;
  Path physical;

  @BeforeEach
  void setup() throws Exception {
    file = file("video.mp4", null);
    physical = storage.resolve(file.getStoragePath());
    Files.write(physical, new byte[2097152]);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    mvc =
        MockMvcBuilders.standaloneSetup(new FileController(fileService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void streamsFullAndPartialFilesAndRejectsInvalidRanges() throws Exception {
    mvc.perform(get("/api/files/stream/" + file.getId()))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    var response =
        mvc.perform(get("/api/files/stream/" + file.getId()).header("Range", "bytes=100-199"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 100-199/2097152"))
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsByteArray()).hasSize(100);
    mvc.perform(get("/api/files/stream/" + file.getId()).header("Range", "bytes=99999999-"))
        .andExpect(status().isRequestedRangeNotSatisfiable());
    Files.write(physical, new byte[0]);
    mvc.perform(get("/api/files/stream/" + file.getId())).andExpect(status().isOk());
  }

  @Test
  void failedDiskDeletionPersistsDeletingAndCanBeReconciled() throws Exception {
    Path blocked = storage.root().resolve(UUID.randomUUID().toString());
    Files.createDirectory(blocked);
    file.setStoragePath(storage.key(blocked));
    files.saveAndFlush(file);
    mvc.perform(delete("/api/files/" + file.getId())).andExpect(status().isAccepted());
    assertThat(files.findById(file.getId()).orElseThrow().getStatus()).isEqualTo("Deleting");
    assertThatThrownBy(() -> fileService.requireOwnedFile(file.getId(), user))
        .isInstanceOf(DomainException.class);
    Files.delete(blocked);
    assertThat(fileService.retryDeletion(files.findById(file.getId()).orElseThrow())).isTrue();
    assertThat(files.existsById(file.getId())).isFalse();
  }

  @Test
  void activeConversionsBlockSourceDeletion() {
    String job = jobs.enqueueConversion(user, file.getId());
    assertThatThrownBy(() -> fileService.deleteFile(file.getId(), user))
        .isInstanceOf(DomainException.class);
    jobs.cancel(job, user);
    fileService.deleteFile(file.getId(), user);
    assertThat(physical).doesNotExist();
    assertThat(files.existsById(file.getId())).isFalse();
  }

  @Test
  void directUploadWithRequestKeyDoesNotCreateDuplicates() throws Exception {
    var part = new MockMultipartFile("file", "new.txt", "text/plain", new byte[] {1, 2, 3});
    MediaFile a = fileService.uploadFile(part, null, user, "direct1"),
        b = fileService.uploadFile(part, null, user, "direct1");
    assertThat(b.getId()).isEqualTo(a.getId());
    assertThatThrownBy(
            () ->
                fileService.uploadFile(
                    new MockMultipartFile("file", "new.txt", "text/plain", new byte[] {3, 2, 1}),
                    null,
                    user,
                    "direct1"))
        .isInstanceOf(DomainException.class);
    fileService.renameFile(a.getId(), "renamed.txt", user);
    assertThat(files.findById(a.getId()).orElseThrow().getTitle()).isEqualTo("renamed.txt");
    assertThatThrownBy(() -> fileService.renameFile(a.getId(), "renamed.html", user))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () -> fileService.getFileForResponse(a.getId(), UUID.randomUUID().toString()))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void activeContentIsDownloadedWithSandboxHeaders() throws Exception {
    var html = file("untrusted.html", null);
    mvc.perform(get("/api/files/stream/" + html.getId()))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
  }
}

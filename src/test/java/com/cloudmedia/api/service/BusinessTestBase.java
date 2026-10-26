package com.cloudmedia.api.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmedia.api.cache.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
      "app.jobs.output-max-bytes=1024",
      "app.quota.active-jobs=3",
      "app.quota.running-jobs=1"
    })
@Import({
  AccountTransactions.class,
  QuotaService.class,
  UploadCoordinator.class,
  FileStorage.class,
  MediaService.class,
  FileService.class,
  FileShareService.class,
  FolderService.class,
  MediaJobService.class,
  JobEvents.class,
  DriveListingService.class,
  BusinessTestBase.Config.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.test.context.event.RecordApplicationEvents
abstract class BusinessTestBase {
  static final Path root =
      Path.of("target", "workflow-tests", UUID.randomUUID().toString()).toAbsolutePath();

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("app.storage.base-dir", () -> root.resolve("media").toString());
    registry.add("app.storage.temp-dir", () -> root.resolve("temp").toString());
  }

  @Autowired UserRepository users;
  @Autowired MediaFileRepository files;
  @Autowired FolderRepository folders;
  @Autowired MediaJobRepository jobRows;
  @Autowired JobOutboxRepository outbox;
  @Autowired UploadSessionRepository sessions;
  @Autowired AccountTransactions accounts;
  @Autowired MediaJobService jobs;
  @Autowired MediaService media;
  @Autowired UploadCoordinator uploads;
  @Autowired FileService fileService;
  @Autowired FileShareRepository shareRows;
  @Autowired FileShareService fileShares;
  @Autowired FolderService folderService;
  @Autowired FileStorage storage;
  @Autowired DriveCache cache;
  @Autowired DriveListingService listings;
  @Autowired org.springframework.test.context.event.ApplicationEvents events;
  String user;

  @BeforeEach
  void owner() {
    outbox.deleteAllInBatch();
    jobRows.deleteAllInBatch();
    sessions.deleteAllInBatch();
    shareRows.deleteAllInBatch();
    files.deleteAllInBatch();
    folders.deleteAllInBatch();
    users.deleteAllInBatch();
    user = UUID.randomUUID().toString();
    users.saveAndFlush(new User(user, user, null, "hash", LocalDateTime.now()));
    clearInvocations(cache);
  }

  MediaFile file(String name, String folder) throws Exception {
    Path output = storage.allocate(UUID.randomUUID().toString(), MediaService.fileExtension(name));
    Files.writeString(output, "data");
    return files.saveAndFlush(
        new MediaFile(
            UUID.randomUUID().toString(),
            name,
            storage.key(output),
            MediaService.fileExtension(name),
            4L,
            folder,
            user,
            "Ready",
            "Local"));
  }

  MediaFile result(MediaJobService.Execution execution) throws Exception {
    Path output = storage.allocate(execution.token(), "mp4");
    Files.writeString(output, "result");
    return new MediaFile(
        execution.job().getId(),
        "result.mp4",
        storage.key(output),
        "mp4",
        6L,
        execution.job().getFolderId(),
        user,
        "Ready",
        "YouTube");
  }

  @TestConfiguration
  static class Config {
    @Bean
    DriveCache cache() {
      DriveCache cache = mock(DriveCache.class);
      when(cache.get(anyString(), nullable(String.class), any()))
          .thenAnswer(call -> ((Supplier<DriveContents>) call.getArgument(2)).get());
      return cache;
    }
  }
}

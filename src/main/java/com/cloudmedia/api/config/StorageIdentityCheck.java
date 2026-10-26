package com.cloudmedia.api.config;

import com.cloudmedia.api.entity.StorageIdentity;
import com.cloudmedia.api.repository.StorageIdentityRepository;
import com.cloudmedia.api.service.FileStorage;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.storage.verify-identity",
    havingValue = "true",
    matchIfMissing = true)
public class StorageIdentityCheck implements ApplicationRunner {
  private final FileStorage storage;
  private final StorageIdentityRepository identities;
  private final String temp;

  public StorageIdentityCheck(
      FileStorage storage,
      StorageIdentityRepository identities,
      @Value("${app.storage.temp-dir:./storage/temp}") String temp) {
    this.storage = storage;
    this.identities = identities;
    this.temp = temp;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    check("media", storage.root());
    check("temp", Path.of(temp).toAbsolutePath().normalize());
  }

  private void check(String kind, Path path) throws Exception {
    Files.createDirectories(path);
    Path marker = path.resolve(".media-cloud-volume");
    try {
      Files.writeString(marker, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException exists) {
    }
    String token = Files.readString(marker).trim();
    UUID.fromString(token);
    StorageIdentity identity = identities.findById(kind).orElse(null);
    if (identity == null) {
      try {
        identity = identities.saveAndFlush(new StorageIdentity(kind, token));
      } catch (org.springframework.dao.DataIntegrityViolationException competingNode) {
        identity = identities.findById(kind).orElseThrow();
      }
    }
    if (!token.equals(identity.getToken()))
      throw new IllegalStateException(
          "Storage "
              + kind
              + " không trùng volume đã đăng ký trong database. Các node phải mount chung volume và"
              + " giữ file .media-cloud-volume.");
  }
}

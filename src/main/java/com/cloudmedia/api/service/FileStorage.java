package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileStorage {
  private static final Logger log = LoggerFactory.getLogger(FileStorage.class);
  private final Path base;

  public FileStorage(@Value("${app.storage.base-dir:./storage/media}") String base) {
    this.base = Path.of(base).toAbsolutePath().normalize();
  }

  public Path root() throws IOException {
    Files.createDirectories(base);
    return base.toRealPath();
  }

  public Path objects() throws IOException {
    Path path = root().resolve("objects");
    Files.createDirectories(path);
    if (!path.toRealPath().startsWith(root())) throw new IOException("Storage path escaped");
    return path.toRealPath();
  }

  public Path allocate(String attempt, String extension) throws IOException {
    InputRules.uuid(attempt);
    Path directory = objects().resolve(attempt);
    Files.createDirectory(directory);
    return directory.resolve("content" + (extension.isEmpty() ? "" : "." + extension));
  }

  public String key(Path path) throws IOException {
    Path normalized = canonical(path.toAbsolutePath().normalize());
    if (!normalized.startsWith(root())) throw new IOException("Storage path escaped");
    return root().relativize(normalized).toString().replace('\\', '/');
  }

  public Path resolve(String key) throws IOException {
    Path value = Path.of(key);
    Path result =
        canonical(
            (value.isAbsolute() ? value : root().resolve(value)).toAbsolutePath().normalize());
    if (!result.startsWith(root())) throw DomainException.missing();
    return result;
  }

  private Path canonical(Path path) throws IOException {
    // Resolve existing ancestors and Windows short names before checking confinement.
    Deque<Path> missing = new ArrayDeque<>();
    Path ancestor = path;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      missing.addFirst(ancestor.getFileName());
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) throw new IOException("Storage ancestor missing");
    Path resolved = ancestor.toRealPath();
    for (Path part : missing) resolved = resolved.resolve(part);
    return resolved.normalize();
  }

  public void cleanup(Path output) {
    if (output == null) return;
    try {
      cleanupAttempt(output.toAbsolutePath().normalize().getParent());
    } catch (IOException | RuntimeException error) {
      log.warn("Chưa dọn được file tạm của lượt xử lý");
    }
  }

  public void cleanupAttempt(Path directory) throws IOException {
    Path target = directory.toAbsolutePath().normalize();
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isSymbolicLink(target)
        || !target.getParent().equals(objects())
        || !target.toRealPath().getParent().equals(objects()))
      throw new IOException("Unsafe cleanup path");
    InputRules.uuid(target.getFileName().toString());
    try (var paths = Files.walk(target)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  public void delete(String key) throws IOException {
    Path path = resolve(key);
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Expected a file");
    Files.deleteIfExists(path);
  }
}

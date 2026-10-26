package com.cloudmedia.api.controller;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.service.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
public class YouTubeController {
  private final MediaJobService jobs;
  private final FolderService folders;

  public YouTubeController(MediaJobService jobs, FolderService folders) {
    this.jobs = jobs;
    this.folders = folders;
  }

  public record Request(String url, String folderId) {}

  @PostMapping("/youtube")
  public ResponseEntity<?> fetchYouTubeVideo(
      @RequestBody Request request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    URI uri;
    try {
      uri = URI.create(request.url() == null ? "" : request.url().trim());
      String host = uri.getHost();
      if (request.url() == null
          || request.url().length() > 2048
          || !("https".equalsIgnoreCase(uri.getScheme())
              || "http".equalsIgnoreCase(uri.getScheme()))
          || host == null
          || uri.getUserInfo() != null
          || !(host.equalsIgnoreCase("youtu.be")
              || host.equalsIgnoreCase("youtube.com")
              || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com"))
          || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException error) {
      throw DomainException.invalid("Vui lòng nhập đường dẫn YouTube hợp lệ");
    }
    String user = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    String folder =
        request.folderId() == null || request.folderId().isBlank() ? null : request.folderId();
    if (folder != null) folders.getFolderById(folder, user);
    String id =
        key == null
            ? jobs.enqueueYouTube(user, uri.toString(), folder)
            : jobs.enqueueYouTube(user, uri.toString(), folder, key);
    return ResponseEntity.accepted().body(Map.of("jobId", id, "status", "PENDING_DISPATCH"));
  }
}

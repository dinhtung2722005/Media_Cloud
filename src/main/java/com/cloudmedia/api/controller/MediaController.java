package com.cloudmedia.api.controller;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.service.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media")
public class MediaController {
  private final MediaJobService jobs;
  private final FileService files;

  public MediaController(MediaJobService jobs, FileService files) {
    this.jobs = jobs;
    this.files = files;
  }

  public record ConvertRequest(String fileId, String inputPath, String outputPath) {}

  @PostMapping("/convert-to-mp3")
  public ResponseEntity<?> convert(
      @RequestBody ConvertRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    if (request.fileId() == null
        || request.fileId().isBlank()
        || request.fileId().length() > 36
        || request.inputPath() != null
        || request.outputPath() != null)
      throw DomainException.invalid("Chỉ cung cấp fileId để chuyển đổi");
    String user = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    files.requireOwnedFile(request.fileId(), user);
    String id =
        key == null
            ? jobs.enqueueConversion(user, request.fileId())
            : jobs.enqueueConversion(user, request.fileId(), key);
    return ResponseEntity.accepted()
        .body(Map.of("fileId", request.fileId(), "jobId", id, "status", "PENDING_DISPATCH"));
  }
}

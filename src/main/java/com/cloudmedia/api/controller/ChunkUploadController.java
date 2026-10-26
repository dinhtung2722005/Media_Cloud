package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaService;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class ChunkUploadController {
  private final MediaService media;

  public ChunkUploadController(MediaService media) {
    this.media = media;
  }

  private String user() {
    return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  public record InitializeRequest(
      String uploadId,
      String filename,
      Long size,
      Integer totalChunks,
      String folderId,
      String sha256) {
    public InitializeRequest {
      if (size == null || totalChunks == null)
        throw com.cloudmedia.api.domain.DomainException.invalid(
            "Cần cung cấp kích thước và số mảnh");
    }
  }

  public record MergeRequest(
      String fileGuid, String originalFilename, int totalChunks, String folderId) {}

  @PostMapping("/uploads")
  public Object initialize(@RequestBody InitializeRequest request) throws java.io.IOException {
    String id = request.uploadId() == null ? UUID.randomUUID().toString() : request.uploadId();
    media.initialize(
        user(),
        id,
        request.filename(),
        request.size(),
        request.totalChunks(),
        request.folderId(),
        request.sha256());
    return media.status(user(), id);
  }

  @GetMapping("/uploads/{id}")
  public Object status(@PathVariable String id) throws java.io.IOException {
    return media.status(user(), id);
  }

  @DeleteMapping("/uploads/{id}")
  public ResponseEntity<?> abort(@PathVariable String id) {
    media.abort(user(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/upload-chunk")
  public Object uploadChunk(
      @RequestParam("file") MultipartFile chunk,
      @RequestParam String fileGuid,
      @RequestParam int chunkIndex,
      @RequestParam int totalChunks)
      throws java.io.IOException {
    media.saveChunk(chunk, fileGuid, chunkIndex, totalChunks, user());
    return Map.of("chunkIndex", chunkIndex);
  }

  @PostMapping("/merge-chunks")
  public ResponseEntity<?> mergeChunks(@RequestBody MergeRequest request)
      throws java.io.IOException {
    return ResponseEntity.status(201)
        .body(
            ApiViews.FileView.from(
                media.mergeChunks(
                    request.fileGuid(),
                    request.originalFilename(),
                    request.totalChunks(),
                    user(),
                    request.folderId())));
  }
}

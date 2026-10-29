package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.FileService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {
  private final FileService files;

  public FileController(FileService files) {
    this.files = files;
  }

  private String user() {
    return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  public record RenameRequest(String title) {}

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadFile(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String folderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key)
      throws java.io.IOException {
    return ResponseEntity.status(201)
        .body(
            ApiViews.FileView.from(
                key == null
                    ? files.uploadFile(file, folderId, user())
                    : files.uploadFile(file, folderId, user(), key)));
  }

  @GetMapping("/download/{id}")
  public ResponseEntity<?> downloadFile(@PathVariable String id) throws java.io.IOException {
    var file = files.getFileForResponse(id, user());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.getFilename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(file.getMimeType()))
        .body(file.getResource());
  }

  @GetMapping("/stream/{id}")
  public ResponseEntity<?> streamFile(@PathVariable String id) throws java.io.IOException {
    var file = files.getFileForResponse(id, user());
    String type = file.getMimeType();
    boolean inline =
        type.startsWith("video/")
            || type.startsWith("audio/")
            || java.util.Set.of(
                    "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp")
                .contains(type);
    var response =
        ResponseEntity.ok()
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header("X-Content-Type-Options", "nosniff");
    if (!inline)
      response
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename(file.getFilename(), StandardCharsets.UTF_8)
                  .build()
                  .toString())
          .header("Content-Security-Policy", "sandbox; default-src 'none'");
    return response
        .contentType(MediaType.parseMediaType(inline ? type : "application/octet-stream"))
        .body(file.getResource());
  }

  @PutMapping("/{id}")
  public Object renameFile(@PathVariable String id, @RequestBody RenameRequest request) {
    files.renameFile(id, request.title(), user());
    return Map.of("message", "Đã đổi tên tệp");
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> deleteFile(@PathVariable String id) {
    return files.deleteAndTry(id, user())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.accepted()
            .body(
                Map.of(
                    "status", "DELETING", "message", "Đã ghi nhận xóa, hệ thống đang dọn dữ liệu"));
  }
}

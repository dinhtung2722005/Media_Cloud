package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.FileShareService;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/{fileId}/shares")
public class FileShareController {
  private final FileShareService shares;

  public FileShareController(FileShareService shares) {
    this.shares = shares;
  }

  public record CreateRequest(Long expiresInHours) {}

  private String user() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  @PostMapping
  public ResponseEntity<?> create(
      @PathVariable String fileId, @RequestBody(required = false) CreateRequest request) {
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(shares.create(fileId, user(), request == null ? null : request.expiresInHours()));
  }

  @GetMapping
  public ResponseEntity<?> list(@PathVariable String fileId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(shares.list(fileId, user()));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> revoke(@PathVariable String fileId, @PathVariable String id) {
    shares.revoke(fileId, id, user());
    return ResponseEntity.noContent().build();
  }
}

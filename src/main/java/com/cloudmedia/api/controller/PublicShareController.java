package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicShareController {
  private final FileShareService shares;

  public PublicShareController(FileShareService shares) {
    this.shares = shares;
  }

  @GetMapping({"/s/{token}", "/share.html"})
  public ResponseEntity<?> page() {
    return secure(ResponseEntity.ok())
        .contentType(MediaType.TEXT_HTML)
        .body(new ClassPathResource("static/share.html"));
  }

  @GetMapping("/api/public/shares/{token}")
  public ResponseEntity<?> metadata(@PathVariable String token) {
    return secure(ResponseEntity.ok()).body(shares.metadata(token));
  }

  @GetMapping("/api/public/shares/{token}/stream")
  public ResponseEntity<?> stream(@PathVariable String token) throws java.io.IOException {
    var file = shares.resource(token);
    String mime = file.mimeType();
    boolean inline =
        mime.startsWith("video/")
            || mime.startsWith("audio/")
            || Set.of(
                    "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp")
                .contains(mime);
    var response = secure(ResponseEntity.ok()).header(HttpHeaders.ACCEPT_RANGES, "bytes");
    if (!inline)
      response.header(
          HttpHeaders.CONTENT_DISPOSITION,
          ContentDisposition.attachment()
              .filename(file.filename(), StandardCharsets.UTF_8)
              .build()
              .toString());
    return response
        .contentType(MediaType.parseMediaType(inline ? mime : "application/octet-stream"))
        .body(file.resource());
  }

  @GetMapping("/api/public/shares/{token}/download")
  public ResponseEntity<?> download(@PathVariable String token) throws java.io.IOException {
    var file = shares.resource(token);
    return secure(ResponseEntity.ok())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(file.resource());
  }

  private ResponseEntity.BodyBuilder secure(ResponseEntity.BodyBuilder response) {
    return response
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Robots-Tag", "noindex, nofollow")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; media-src"
                + " 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none';"
                + " form-action 'none'");
  }
}

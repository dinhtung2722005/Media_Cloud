package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.FolderService;
import com.cloudmedia.api.service.MediaJobService;
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

    @PostMapping("/youtube")
    public ResponseEntity<?> fetchYouTubeVideo(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
            String host = uri.getHost();
            if (url == null || url.length() > 2048
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || uri.getUserInfo() != null
                    || !(host.equalsIgnoreCase("youtu.be") || host.equalsIgnoreCase("youtube.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng nhập đường dẫn YouTube hợp lệ"));
        }
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String folderId = request.get("folderId");
        if (folderId != null && folderId.isBlank()) folderId = null;
        if (folderId != null) {
            try { folders.getFolderById(folderId, userId); }
            catch (RuntimeException exception) {
                return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy thư mục"));
            }
        }
        String jobId = jobs.enqueueYouTube(userId, uri.toString(), folderId);
        return ResponseEntity.accepted().body(Map.of("message", "Đã gửi yêu cầu tải YouTube", "jobId", jobId, "status", "QUEUED"));
    }
}

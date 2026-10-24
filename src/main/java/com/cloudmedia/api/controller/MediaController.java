package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.FileService;
import com.cloudmedia.api.service.MediaJobService;
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

    @PostMapping("/convert-to-mp3")
    public ResponseEntity<?> convert(@RequestBody Map<String, String> request) {
        String fileId = request.get("fileId");
        if (fileId == null || fileId.isBlank() || request.containsKey("inputPath") || request.containsKey("outputPath")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chỉ cung cấp fileId để chuyển đổi"));
        }
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try { files.requireOwnedFile(fileId, userId); }
        catch (IllegalArgumentException exception) {
            return ResponseEntity.status(404).body(Map.of("error", exception.getMessage()));
        }
        String jobId = jobs.enqueueConversion(userId, fileId);
        return ResponseEntity.accepted().body(Map.of("fileId", fileId, "jobId", jobId, "status", "QUEUED"));
    }
}

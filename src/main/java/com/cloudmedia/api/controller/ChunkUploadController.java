package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class ChunkUploadController {
    private final MediaService mediaService;

    public ChunkUploadController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(@RequestParam("file") MultipartFile chunk,
                                         @RequestParam String fileGuid,
                                         @RequestParam int chunkIndex,
                                         @RequestParam int totalChunks) {
        try {
            mediaService.saveChunk(chunk, fileGuid, chunkIndex, totalChunks, currentUserId());
            return ResponseEntity.ok(Map.of("chunkIndex", chunkIndex));
        } catch (ResponseStatusException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", exception.getReason()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không thể lưu mảnh file"));
        }
    }

    @PostMapping("/merge-chunks")
    public ResponseEntity<?> mergeChunks(@RequestBody MergeRequest request) {
        try {
            return ResponseEntity.status(201).body(mediaService.mergeChunks(request.fileGuid(),
                    request.originalFilename(), request.totalChunks(), currentUserId(), request.folderId()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không thể gộp file"));
        }
    }

    private String currentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public record MergeRequest(String fileGuid, String originalFilename, int totalChunks, String folderId) {}
}

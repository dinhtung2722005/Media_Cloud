package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.service.FileService;
import com.cloudmedia.api.service.FileService.FileResourceWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    // Controller chỉ tiêm duy nhất một mình FileService
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    private String getCurrentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    // [POST] /api/files/upload
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) String folderId) {
        try {
            MediaFile mediaFile = fileService.uploadFile(file, folderId, getCurrentUserId());
            return ResponseEntity.status(HttpStatus.CREATED).body(mediaFile);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    // [GET] /api/files/download/{fileId} -> Ép tải về
    @GetMapping("/download/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId) {
        return buildFileResponse(fileId, "attachment");
    }

    // [GET] /api/files/stream/{fileId} -> Phát trực tiếp
    @GetMapping("/stream/{fileId}")
    public ResponseEntity<?> streamFile(@PathVariable String fileId) {
        return buildFileResponse(fileId, "inline");
    }

    // [DELETE] /api/files/{fileId}
    @DeleteMapping("/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String fileId) {
        try {
            fileService.deleteFile(fileId, getCurrentUserId());
            return ResponseEntity.ok(Map.of("message", "Đã xóa file thành công và giải phóng ổ cứng!"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    // Hàm bổ trợ đóng gói HTTP Header dùng chung cho cả Download và Stream
    private ResponseEntity<?> buildFileResponse(String fileId, String dispositionType) {
        try {
            FileResourceWrapper wrapper = fileService.getFileForResponse(fileId, getCurrentUserId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, dispositionType + "; filename=\"" + wrapper.getFilename() + "\"")
                    .contentType(MediaType.parseMediaType(wrapper.getMimeType()))
                    .body(wrapper.getResource());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}
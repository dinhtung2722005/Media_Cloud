package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.service.FileService;
import com.cloudmedia.api.service.FileService.FileResourceWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

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

    @GetMapping("/stream/{fileId}")
    public ResponseEntity<?> streamFile(@PathVariable String fileId) {
        try {
            FileResourceWrapper wrapper = fileService.getFileForResponse(fileId, getCurrentUserId());
            // Spring MVC serves the full Resource or handles Range/416 transparently.
            return ResponseEntity.ok().header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.parseMediaType(wrapper.getMimeType()))
                    .body(wrapper.getResource());
        } catch (Exception e) {
            // Khi bị lỗi, không trả về Map JSON nữa mà chỉ trả về mã 404 (Not Found)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
 
    @PutMapping("/{fileId}")
    public ResponseEntity<?> renameFile(
            @PathVariable String fileId, 
            @RequestBody Map<String, String> request) {
        try {
            String newTitle = request.get("title");
            
            // Gọi hàm từ FileService mà bạn vừa thêm lúc nãy
            fileService.renameFile(fileId, newTitle, getCurrentUserId());
            
            return ResponseEntity.ok(Map.of("message", "Đổi tên tệp thành công!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // [DELETE] /api/files/{fileId}
    @DeleteMapping("/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String fileId) {
        try {
            fileService.deleteFile(fileId, getCurrentUserId());
            return ResponseEntity.ok(Map.of("message", "Đã xóa file thành công và giải phóng ổ cứng!"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Không thể xóa file; vui lòng thử lại"));
        }
    }

    // Hàm bổ trợ đóng gói HTTP Header dùng cho luồng Tải Về (Download)
    private ResponseEntity<?> buildFileResponse(String fileId, String dispositionType) {
        try {
            FileResourceWrapper wrapper = fileService.getFileForResponse(fileId, getCurrentUserId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(wrapper.getFilename(), StandardCharsets.UTF_8).build().toString())
                    .contentType(MediaType.parseMediaType(wrapper.getMimeType()))
                    .body(wrapper.getResource());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}

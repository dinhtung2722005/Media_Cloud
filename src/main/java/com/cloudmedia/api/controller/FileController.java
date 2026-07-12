package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.service.FileService;
import com.cloudmedia.api.service.FileService.FileResourceWrapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
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
    public ResponseEntity<ResourceRegion> streamFile(@PathVariable String fileId, @RequestHeader HttpHeaders headers) {
        try {
            FileResourceWrapper wrapper = fileService.getFileForResponse(fileId, getCurrentUserId());
            Resource video = wrapper.getResource();
            long contentLength = video.contentLength();
            long chunkSize = 10L * 1024 * 1024;
            // Đọc Header Range từ trình duyệt gửi lên
            HttpRange range = headers.getRange().isEmpty() ? null : headers.getRange().get(0);

            if (range != null) {
                long start = range.getRangeStart(contentLength);
                long end = range.getRangeEnd(contentLength);
                long rangeLength = Math.min(chunkSize, end - start + 1); 
                ResourceRegion region = new ResourceRegion(video, start, rangeLength);

                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT) // Mã 206
                        .contentType(MediaType.parseMediaType(wrapper.getMimeType()))
                        .body(region);
            } else {
                long rangeLength = Math.min(1024 * 1024, contentLength);
                ResourceRegion region = new ResourceRegion(video, 0, rangeLength);
                
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .contentType(MediaType.parseMediaType(wrapper.getMimeType()))
                        .body(region);
            }
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
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    // Hàm bổ trợ đóng gói HTTP Header dùng cho luồng Tải Về (Download)
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
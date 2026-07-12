package com.cloudmedia.api.controller;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.entity.Folder;
import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.MediaFileRepository;
import com.cloudmedia.api.service.FolderService;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;
    private final MediaFileRepository mediaFileRepository; // [BỔ SUNG] Khai báo Repository quản lý file
    private final FolderRepository folderRepository; // [BỔ SUNG] Khai báo Repository quản lý thư mục
    // [CẬP NHẬT] Tiêm thêm MediaFileRepository vào Constructor
    public FolderController(FolderService folderService, MediaFileRepository mediaFileRepository, FolderRepository folderRepository) {
        this.folderService = folderService;
        this.folderRepository = folderRepository;
        this.mediaFileRepository = mediaFileRepository;
    }

    private String getCurrentUserId() {
        // Bóc tách UserId để ngăn chặn IDOR
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    // [POST] /api/folders - Tạo thư mục mới (Status: 201 Created)
    @PostMapping
    public ResponseEntity<?> createFolder(@RequestBody Map<String, String> request) {
        try {
            String folderName = request.get("folderName");
            String parentFolderId = request.get("parentId");
            
            Folder newFolder = folderService.createFolder(folderName, parentFolderId, getCurrentUserId());
            
            // Chuẩn REST: Tạo URI của tài nguyên vừa được khởi tạo để gắn vào Header Location
            URI location = ServletUriComponentsBuilder
                    .fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(newFolder.getId())
                    .toUri();

            return ResponseEntity.created(location).body(newFolder);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    // [GET] /api/folders - Lấy danh sách thư mục (Status: 200 OK)
    @GetMapping
    public ResponseEntity<List<Folder>> getFolders(@RequestParam(required = false) String parentId) {
        List<Folder> folders = folderService.getFolders(getCurrentUserId(), parentId);
        return ResponseEntity.ok(folders);
    }

    
    @GetMapping("/{folderId}")
    public ResponseEntity<?> getFolderById(@PathVariable String folderId) {
        try {
            String userId = getCurrentUserId();
            
            // 1. Lấy thông tin chi tiết của Thư mục
            Folder folder = folderService.getFolderById(folderId, userId);
            List<Folder> subFolders = folderRepository.findByParentFolderIdAndUserId(folderId, userId);
            // 2. Lấy toàn bộ File nằm trong Thư mục này
            List<MediaFile> files = mediaFileRepository.findByFolderIdAndUserId(folderId, userId);

            // 3. Đóng gói cả Thư mục và Danh sách File vào một JSON duy nhất
            Map<String, Object> response = new HashMap<>();
            response.put("folderInfo", folder);
            response.put("subFolders", subFolders);
            response.put("files", files);

            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    // [PUT] /api/folders/{folderId} - Cập nhật/Đổi tên thư mục (Status: 200 OK)
    @PutMapping("/{folderId}")
    public ResponseEntity<?> updateFolder(@PathVariable String folderId, @RequestBody Map<String, String> request) {
        try {
            String newFolderName = request.get("folderName");
            Folder updatedFolder = folderService.updateFolderName(folderId, newFolderName, getCurrentUserId());
            return ResponseEntity.ok(updatedFolder);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    // [DELETE] /api/folders/{folderId} - Xóa thư mục (Status: 204 No Content)
    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> deleteFolder(@PathVariable String folderId) {
        try {
            folderService.deleteFolder(folderId, getCurrentUserId());
            // Chuẩn REST: Xóa thành công không cần trả về body
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            // Nếu không xóa được do còn chứa dữ liệu, trả về 409 Conflict hoặc 400 Bad Request
            return ResponseEntity.status(HttpStatus.CONFLICT).build(); 
        }
    }
}
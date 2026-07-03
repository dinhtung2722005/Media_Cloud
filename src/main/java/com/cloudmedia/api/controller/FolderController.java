package com.cloudmedia.api.controller;

import com.cloudmedia.api.entity.Folder;
import com.cloudmedia.api.service.FolderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
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
            String parentFolderId = request.get("parentFolderId");
            
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

    // [GET] /api/folders/{folderId} - Lấy chi tiết 1 thư mục (Status: 200 OK hoặc 404)
    @GetMapping("/{folderId}")
    public ResponseEntity<?> getFolderById(@PathVariable String folderId) {
        try {
            Folder folder = folderService.getFolderById(folderId, getCurrentUserId());
            return ResponseEntity.ok(folder);
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
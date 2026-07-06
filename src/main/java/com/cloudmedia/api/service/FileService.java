package com.cloudmedia.api.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.repository.MediaFileRepository;

@Service
public class FileService {

    private final MediaFileRepository mediaFileRepository;
    private final FolderRepository folderRepository;

    @Value("${app.storage.base-dir}")
    private String baseStorageDir;

    public FileService(MediaFileRepository mediaFileRepository, FolderRepository folderRepository) {
        this.mediaFileRepository = mediaFileRepository;
        this.folderRepository = folderRepository;
    }

    // 1. NGHIỆP VỤ UPLOAD FILE
    public MediaFile uploadFile(MultipartFile file, String folderId, String userId) throws IOException {
        // Kiểm tra bảo mật Thư mục (IDOR)
        if (folderId != null && !folderId.trim().isEmpty()) {
            boolean isFolderValid = folderRepository.findById(folderId)
                    .map(folder -> folder.getUserId().equals(userId))
                    .orElse(false);
            if (!isFolderValid) {
                throw new RuntimeException("Không có quyền truy cập thư mục này!");
            }
        }

        // Sinh tên file vật lý ẩn danh (UUID)
        String physicalFileId = UUID.randomUUID().toString();
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
        }
        
        String physicalFileName = physicalFileId + (extension.isEmpty() ? "" : "." + extension);
        Path targetLocation = Paths.get(baseStorageDir).resolve(physicalFileName);

        // Copy luồng dữ liệu xuống ổ đĩa vật lý
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

        // Chuẩn bị thực thể dữ liệu để lưu DB
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(physicalFileId);
        mediaFile.setTitle(originalFilename);
        mediaFile.setStoragePath(targetLocation.toString());
        mediaFile.setExtension(extension);
        mediaFile.setFileSize(file.getSize());
        mediaFile.setFolderId((folderId != null && !folderId.trim().isEmpty()) ? folderId : null);
        mediaFile.setUserId(userId);
        mediaFile.setStatus("Ready");
        mediaFile.setSource("Local_Upload");

        return mediaFileRepository.save(mediaFile);
    }

    // 2. NGHIỆP VỤ LẤY ĐỐI TƯỢNG FILE (Dùng chung cho Download và Stream)
    public FileResourceWrapper getFileForResponse(String fileId, String userId) throws IOException {
        Optional<MediaFile> fileOptional = mediaFileRepository.findById(fileId);
        if (fileOptional.isEmpty() || !fileOptional.get().getUserId().equals(userId)) {
            throw new RuntimeException("File không tồn tại hoặc bạn không có quyền truy cập!");
        }

        MediaFile mediaFile = fileOptional.get();
        Path filePath = Paths.get(mediaFile.getStoragePath());
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            throw new RuntimeException("File vật lý không còn tồn tại trên hệ thống máy chủ!");
        }

        String mimeType = Files.probeContentType(filePath);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        return new FileResourceWrapper(resource, mimeType, mediaFile.getTitle());
    }

    // 3. NGHIỆP VỤ XÓA FILE
    public void deleteFile(String fileId, String userId) {
        Optional<MediaFile> fileOptional = mediaFileRepository.findById(fileId);
        if (fileOptional.isEmpty() || !fileOptional.get().getUserId().equals(userId)) {
            throw new RuntimeException("File không tồn tại hoặc bạn không có quyền xóa!");
        }

        MediaFile mediaFile = fileOptional.get();

        // Xóa file vật lý
        Path filePath = Paths.get(mediaFile.getStoragePath());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            System.err.println("Không thể xóa file vật lý, bỏ qua để làm sạch DB: " + e.getMessage());
        }

        // Xóa dòng thông tin trong DB
        mediaFileRepository.delete(mediaFile);
    }

    // --- LỚP BỌC DỮ LIỆU ĐỂ TRẢ VỀ CHO CONTROLLER ---
    public static class FileResourceWrapper {
        private final Resource resource;
        private final String mimeType;
        private final String filename;

        public FileResourceWrapper(Resource resource, String mimeType, String filename) {
            this.resource = resource;
            this.mimeType = mimeType;
            this.filename = filename;
        }

        public Resource getResource() { return resource; }
        public String getMimeType() { return mimeType; }
        public String getFilename() { return filename; }
    }
}
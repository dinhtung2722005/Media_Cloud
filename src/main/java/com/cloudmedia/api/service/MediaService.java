package com.cloudmedia.api.service;
import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.MediaFileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;
@Service
public class MediaService {
    private final MediaFileRepository mediaFileRepository;
    @Value("${app.storage.temp-dir}")
    private String tempStorageDir;

    @Value("${app.storage.base-dir}")
    private String baseStorageDir;
    public MediaService(MediaFileRepository mediaFileRepository) {
        this.mediaFileRepository = mediaFileRepository;
    }
    public MediaFile getMediaFileById(String fileId,String userId) {
        return mediaFileRepository.findById(fileId)
        .filter(file -> file.getUserId().equals(userId))
        .orElseThrow(() -> new RuntimeException("File không tồn tại hoặc bạn không có quyền truy cập!"));
        }
    @Transactional
    public MediaFile mergeChunks(String fileGuid, String originalFilename, int totalChunks, String userId, String folderId) throws Exception {
        File baseDir = new File(baseStorageDir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        String physicalFileId = UUID.randomUUID().toString();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        Path finalFilePath = Paths.get(baseStorageDir, physicalFileId + extension);
        MessageDigest md5Digest = MessageDigest.getInstance("MD5");
        long totalFileSize = 0;
        try (FileOutputStream fos = new FileOutputStream(finalFilePath.toFile());
             BufferedOutputStream mergingStream = new BufferedOutputStream(fos)) {

            for (int i = 0; i < totalChunks; i++) {
                // Đọc các file tạm dạng {FileGuid}_{ChunkIndex}.tmp[cite: 1]
                Path chunkPath = Paths.get(tempStorageDir, fileGuid + "_" + i + ".tmp");
                File chunkFile = chunkPath.toFile();

                // Bắt lỗi nếu thiếu mảnh file
                if (!chunkFile.exists()) {
                    throw new FileNotFoundException("Thiếu mảnh file thứ " + i + " trong quá trình gộp!");
                }

                try (FileInputStream fis = new FileInputStream(chunkFile);
                     BufferedInputStream in = new BufferedInputStream(fis)) {
                    
                    byte[] buffer = new byte[8192]; // Đọc từng block 8KB
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        mergingStream.write(buffer, 0, bytesRead);
                        // Tính toán kiểm tra mã băm MD5 để xác thực tính toàn vẹn[cite: 1]
                        md5Digest.update(buffer, 0, bytesRead); 
                        totalFileSize += bytesRead;
                    }
                }
                
                // Tiến hành xóa dọn thư mục tạm để giải phóng bộ nhớ[cite: 1]
                Files.deleteIfExists(chunkPath);
            }
        }
        StringBuilder md5Hex = new StringBuilder();
        for (byte b : md5Digest.digest()) {
            md5Hex.append(String.format("%02x", b));
        }
        System.out.println("Mã MD5 của file [" + originalFilename + "]: " + md5Hex.toString());

        // Lưu thông tin siêu dữ liệu (Metadata) vào CSDL
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(physicalFileId);
        mediaFile.setTitle(originalFilename);
        mediaFile.setStoragePath(finalFilePath.toString());
        mediaFile.setExtension(extension.replace(".", ""));
        mediaFile.setFileSize(totalFileSize);
        // Nếu folderId rỗng, gán null để file nằm ở thư mục gốc
        mediaFile.setFolderId((folderId != null && !folderId.trim().isEmpty()) ? folderId : null);
        mediaFile.setUserId(userId);
        mediaFile.setStatus("Ready"); // File đã sẵn sàng sử dụng[cite: 1]
        mediaFile.setSource("Local"); // Ghi nhận nguồn gốc từ thiết bị cục bộ[cite: 1]

        return mediaFileRepository.save(mediaFile);
    }
    @Transactional
    public void deleteMediaFile(String fileId, String userId) throws IOException {
        // Xác thực quyền sở hữu file trước khi xóa
        MediaFile file = getMediaFileById(fileId, userId);
        
        // Xóa file vật lý trên ổ đĩa cứng server
        Path physicalPath = Paths.get(file.getStoragePath());
        Files.deleteIfExists(physicalPath);

        // Xóa siêu dữ liệu (metadata) khỏi Database
        mediaFileRepository.delete(file);
    }
}

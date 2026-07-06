package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.MediaFileRepository;
import org.jobrunr.jobs.annotations.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.cloudmedia.api.repository.FolderRepository;
import java.util.Map;

@Service
public class MediaEngineService {

    private final MediaFileRepository mediaFileRepository;
    private final FolderRepository folderRepository;
    private final SimpMessagingTemplate messagingTemplate;
    @Value("${app.storage.base-dir}")
    private String baseStorageDir;

    public MediaEngineService(MediaFileRepository mediaFileRepository,  FolderRepository folderRepository, SimpMessagingTemplate messagingTemplate) {
        this.mediaFileRepository = mediaFileRepository;
        this.folderRepository = folderRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Tác vụ chạy ngầm: Ép kiểu chuyển đổi video MP4 sang MP3 nhạc nền
     */
    @Job(name = "Chuyển đổi định dạng MP4 sang MP3 - File: %0")
    public void convertMp4ToMp3(String fileId, String inputPath, String outputPath) {
        try {
            String ffmpegPath = "G:\\tools\\ffmpeg.exe";
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(inputPath);
            command.add("-vn"); // Loại bỏ luồng video chỉ giữ lại audio
            command.add("-acodec");
            command.add("libmp3lame");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Log tiến trình ffmpeg ra console nếu cần debug
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("FFmpeg: Chuyển đổi thành công file " + fileId);
                // TODO: Nâng cấp thêm logic cập nhật trạng thái nếu cần
            } else {
                throw new RuntimeException("FFmpeg thất bại với mã thoát: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Lỗi xử lý FFmpeg: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Tác vụ chạy ngầm: Gọi yt-dlp tải video chất lượng cao từ liên kết YouTube
     */
    @Job(name = "Tải Video từ YouTube - Link: %0")
    public void downloadYouTubeVideo(String youtubeUrl, String userId, String folderId) {
        String validFolderId = null;
        if (folderId != null && !folderId.trim().isEmpty()) {
            // Kiểm tra xem thư mục có tồn tại VÀ có đúng của user này không
            boolean isFolderValid = folderRepository.findById(folderId)
                    .map(folder -> folder.getUserId().equals(userId))
                    .orElse(false);

            if (!isFolderValid) {
                // Ném ngoại lệ ngay lập tức, hủy Job, không tải video nữa
                throw new RuntimeException("Cảnh báo bảo mật: Thư mục không tồn tại hoặc bạn không có quyền truy cập (IDOR)!");
            }
            validFolderId = folderId;
        }
        try {
            // 1. Chuẩn bị đường dẫn và tên file vật lý ẩn danh (UUID)
            String physicalFileId = UUID.randomUUID().toString();
            Path finalFilePath = Paths.get(baseStorageDir, physicalFileId + ".mp4");

            // 2. Cấu hình lệnh yt-dlp (Đã giữ lại đường dẫn công cụ G:\tools của bạn)
            String ytDlpPath = "G:\\tools\\yt-dlp.exe";
            String ffmpegFolder = "G:\\tools";
            
            List<String> command = new ArrayList<>();
            command.add(ytDlpPath);
            command.add("--ffmpeg-location");
            command.add(ffmpegFolder);
            command.add("-f");
            command.add("bestvideo+bestaudio/best"); 
            command.add("--merge-output-format");
            command.add("mp4");
            command.add("-o"); // Đổi cờ -P thành -o để chỉ định chính xác tên file đích
            command.add(finalFilePath.toString());
            command.add(youtubeUrl);
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 3. Đọc log chống nghẽn luồng
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("%")) {
                         System.out.println("yt-dlp Log: " + line);
                         messagingTemplate.convertAndSend(
                            "/topic/progress/" + userId, 
                            Map.of(
                                "jobType", "YOUTUBE_DOWNLOAD",
                                "status", "PROCESSING",
                                "progressLine", line // Dòng log chứa tiến độ (VD: "  45.0% of 50MiB...")
                            )
                        );
                    }
                }
            }

            // 4. Kiểm tra thành công và Lưu Metadata vào CSDL
            int exitCode = process.waitFor();
            if (exitCode == 0 && Files.exists(finalFilePath)) {
                
                MediaFile mediaFile = new MediaFile();
                mediaFile.setId(physicalFileId);
                mediaFile.setTitle("YouTube Video - " + physicalFileId.substring(0, 5));
                mediaFile.setStoragePath(finalFilePath.toString());
                mediaFile.setExtension("mp4");
                mediaFile.setFileSize(Files.size(finalFilePath));
                
                // Xử lý folderId linh hoạt (rỗng -> lưu ở thư mục gốc)
                mediaFile.setFolderId((folderId != null && !folderId.trim().isEmpty()) ? folderId : null);
                
                mediaFile.setUserId(userId);
                mediaFile.setStatus("Ready"); 
                mediaFile.setSource("YouTube"); 

                mediaFileRepository.save(mediaFile);
                System.out.println("Tải thành công và đã ánh xạ video vào thư mục của User: " + userId);
                messagingTemplate.convertAndSend(
                    "/topic/progress/" + userId, 
                    Map.of(
                        "jobType", "YOUTUBE_DOWNLOAD",
                        "status", "COMPLETED",
                        "fileId", physicalFileId
                    )
                );
                
            } else {
                throw new RuntimeException("yt-dlp tải thất bại! Mã lỗi: " + exitCode);
            }
        } catch (Exception e) {
            messagingTemplate.convertAndSend(
                "/topic/progress/" + userId, 
                Map.of(
                    "jobType", "YOUTUBE_DOWNLOAD",
                    "status", "FAILED",
                    "message", e.getMessage()
                )
            );
            throw new RuntimeException("Lỗi tiến trình ngầm: " + e.getMessage());
        }
    }
}
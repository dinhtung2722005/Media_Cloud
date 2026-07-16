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
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("FFmpeg: Chuyển đổi thành công file " + fileId);
            } else {
                throw new RuntimeException("FFmpeg thất bại với mã thoát: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Lỗi xử lý FFmpeg: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

   
    @Job(name = "Tải Video từ YouTube - Link: %0")
    public void downloadYouTubeVideo(String youtubeUrl, String userId, String folderId, String username) {
        String validFolderId = null;
        if (folderId != null && !folderId.trim().isEmpty()) {
            // Kiểm tra xem thư mục có tồn tại VÀ có đúng của user này không (IDOR)
            boolean isFolderValid = folderRepository.findById(folderId)
                    .map(folder -> folder.getUserId().equals(userId))
                    .orElse(false);

            if (!isFolderValid) {
                throw new RuntimeException("Cảnh báo bảo mật: Thư mục không tồn tại hoặc bạn không có quyền truy cập!");
            }
            validFolderId = folderId;
        }

        String physicalFileId = UUID.randomUUID().toString();
        String shortId = physicalFileId.substring(0, 6);
        String taskId = "yt-dlp-" + shortId;

        try {
            // 1. Chuẩn bị đường dẫn vật lý
            Path finalFilePath = Paths.get(baseStorageDir, physicalFileId + ".mp4");

            // 2. Cấu hình lệnh yt-dlp
            String ytDlpPath = "G:\\tools\\yt-dlp.exe";
            String ffmpegFolder = "G:\\tools";
            
            List<String> command = new ArrayList<>();
            command.add(ytDlpPath);
            command.add("--ffmpeg-location");
            command.add(ffmpegFolder);
            command.add("--newline");
            command.add("-f");
            command.add("bestvideo+bestaudio/best"); 
            command.add("--merge-output-format");
            command.add("mp4");
            command.add("-o"); 
            command.add(finalFilePath.toString());
            command.add(youtubeUrl);
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 3. Đọc log liên tục và bắn WebSocket
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // yt-dlp thường in ra các dòng có chứa % khi đang tải
                    if (line.contains("%")) {
                         messagingTemplate.convertAndSend(
                            "/topic/progress/global",
                            Map.of(
                                "jobType", "YOUTUBE_DOWNLOAD",
                                "taskId", taskId,
                                "taskName", "Tải YouTube",
                                "status", "PROCESSING",
                                "progressLine", line 
                            )
                        );
                    }
                }
            }

            // 4. Đợi tiến trình hoàn tất và Kiểm tra kết quả
            int exitCode = process.waitFor();
            if (exitCode == 0 && Files.exists(finalFilePath)) {
                
                // Lưu Metadata vào Cơ sở dữ liệu
                MediaFile mediaFile = new MediaFile();
                mediaFile.setId(physicalFileId);
                mediaFile.setTitle("YouTube Video - " + shortId);
                mediaFile.setStoragePath(finalFilePath.toString());
                mediaFile.setExtension("mp4");
                mediaFile.setFileSize(Files.size(finalFilePath));
                mediaFile.setFolderId(validFolderId); // Dùng ID thư mục đã xác thực
                mediaFile.setUserId(userId);
                mediaFile.setStatus("Ready"); 
                mediaFile.setSource("YouTube"); 

                mediaFileRepository.save(mediaFile);
                
                // Bắn thông báo hoàn thành (100%)
                messagingTemplate.convertAndSend(
                    "/topic/progress/global",
                        Map.of(
                        "jobType", "YOUTUBE_DOWNLOAD",
                        "taskId", taskId,
                        "taskName", "Tải YouTube",
                        "status", "COMPLETED",
                        "progress", 100
                    )
                );
                
            } else {
                throw new RuntimeException("yt-dlp tải thất bại! Mã lỗi: " + exitCode);
            }
        } catch (Exception e) {
            messagingTemplate.convertAndSend(
                "/topic/progress/global",
                Map.of(
                    "jobType", "YOUTUBE_DOWNLOAD",
                    "taskId", taskId,
                    "taskName", "Tải YouTube",
                    "status", "FAILED",
                    "message", e.getMessage()
                )
            );
            throw new RuntimeException("Lỗi tiến trình ngầm: " + e.getMessage());
        }
    } 
}
package com.cloudmedia.api.service;

import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaEngineService {

    /**
     * Tác vụ chạy ngầm: Ép kiểu chuyển đổi video MP4 sang MP3 nhạc nền
     * Thẻ @Job giúp JobRunr nhận diện và hiển thị tên tác vụ này trên Dashboard
     */
    @Job(name = "Chuyển đổi định dạng MP4 sang MP3 - File: %0")
    public void convertMp4ToMp3(String fileId, String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn"); // Loại bỏ luồng video chỉ giữ lại audio
            command.add("-acodec");
            command.add("libmp3lame");
            command.add(outputPath);

            // Thực thi tiến trình hệ thống
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Đọc log đầu ra để tránh nghẽn luồng I/O hệ thống
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Log tiến trình ffmpeg ra console nếu cần debug
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("FFmpeg: Chuyển đổi thành công file " + fileId);
                // TODO: Cập nhật trạng thái Ready vào MediaFileRepository[cite: 1]
            } else {
                throw new RuntimeException("FFmpeg thất bại với mã thoát: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Lỗi xử lý FFmpeg: " + e.getMessage());
            // JobRunr sẽ tự động bắt ngoại lệ này và thực hiện cơ chế Retry (thử lại ngầm)
            throw new RuntimeException(e); 
        }
    }

    /**
     * Tác vụ chạy ngầm: Gọi yt-dlp tải video chất lượng cao từ liên kết YouTube[cite: 1]
     */
    @Job(name = "Tải Video từ YouTube - Link: %0")
    public void downloadYouTubeVideo(String youtubeUrl, String downloadFolder) {
        try {
            String ytDlpPath = "G:\\tools\\yt-dlp.exe";
            String ffmpegFolder = "G:\\tools";
            List<String> command = new ArrayList<>();
            command.add(ytDlpPath);
            command.add("--ffmpeg-location");
        command.add(ffmpegFolder);
            command.add("-f");
            command.add("bestvideo+bestaudio/best"); // Chọn chất lượng tốt nhất[cite: 1]
            command.add("--merge-output-format");
            command.add("mp4");
            command.add("-P"); // Chỉ định thư mục lưu trữ vật lý[cite: 1]
            command.add(downloadFolder);
            command.add(youtubeUrl);
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Kỹ thuật bắt dòng % tiến độ của yt-dlp để chuẩn bị đẩy qua SignalR/WebSocket[cite: 1]
                    if (line.contains("%")) {
                         System.out.println("yt-dlp Log: " + line);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("yt-dlp kết thúc với lỗi. Mã lỗi: " + exitCode);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaEngineService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final JobScheduler jobScheduler;
    private final MediaEngineService mediaEngineService;

    // Tiêm các dependency (Không cần @Autowired trên Spring Boot bản mới nếu chỉ có 1 constructor)
    public MediaController(JobScheduler jobScheduler, MediaEngineService mediaEngineService) {
        this.jobScheduler = jobScheduler;
        this.mediaEngineService = mediaEngineService;
    }

    @PostMapping("/convert-to-mp3")
    public ResponseEntity<?> queueFormatConversion(@RequestBody Map<String, String> request) {
        
        // 1. Lấy thông tin từ cục JSON gửi lên qua Postman
        String fileId = request.get("fileId");
        String inputPath = request.get("inputPath");   // Ví dụ: G:\MediaDrive\Downloads\video.mp4
        String outputPath = request.get("outputPath"); // Ví dụ: G:\MediaDrive\Downloads\audio.mp3

        // Kiểm tra an toàn dữ liệu cơ bản
        if (fileId == null || inputPath == null || outputPath == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Vui lòng cung cấp đầy đủ fileId, inputPath và outputPath!"
            ));
        }

        // 2. ĐÈN XANH: Đẩy tác vụ gọi FFmpeg vào hàng đợi ngầm của JobRunr
        jobScheduler.enqueue(() -> mediaEngineService.convertMp4ToMp3(fileId, inputPath, outputPath));

        // 3. Trả về HTTP 202 (Accepted) ngay lập tức cho client
        return ResponseEntity.accepted().body(Map.of(
                "message", "Yêu cầu chuyển đổi định dạng đã được tiếp nhận và đưa vào hàng đợi!",
                "fileId", fileId,
                "status", "Queued"
        ));
    }
}
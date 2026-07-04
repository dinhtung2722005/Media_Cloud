package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaEngineService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/youtube")
public class YouTubeController {

    private final JobScheduler jobScheduler;
    private final MediaEngineService mediaEngineService;

    public YouTubeController(JobScheduler jobScheduler, MediaEngineService mediaEngineService) {
        this.jobScheduler = jobScheduler;
        this.mediaEngineService = mediaEngineService;
    }

    @PostMapping("/fetch")
    public ResponseEntity<?> queueYouTubeDownload(@RequestBody Map<String, String> request) {
        String videoUrl = request.get("url");
        String targetFolder = "/storage/media";

        // ĐÈN XANH: Đẩy tác vụ nặng vào hàng đợi ngầm của JobRunr (Chạy bất đồng bộ hoàn toàn)[cite: 1]
        jobScheduler.enqueue(() -> mediaEngineService.downloadYouTubeVideo(videoUrl, targetFolder));

        // Trả về kết quả ngay lập tức cho người dùng không bị treo trình duyệt
        return ResponseEntity.accepted().body(Map.of(
                "message", "Yêu cầu đã được tiếp nhận và đưa vào hàng đợi xử lý ngầm!",
                "status", "Queued"
        ));
    }
}
package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaEngineService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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

    // Hàm bắt buộc phải có để lấy ID người dùng từ Token (Chống IDOR)
    private String getCurrentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @PostMapping("/fetch")
    public ResponseEntity<?> fetchYouTubeVideo(@RequestBody Map<String, String> request) {
        try {
            String videoUrl = request.get("url");
            
            // Lấy folderId từ request (nếu không truyền lên thì mặc định là chuỗi rỗng)
            String folderId = request.getOrDefault("folderId", ""); 
            
            // Lấy userId của người đang gọi API
            String userId = getCurrentUserId();

            // Truyền ĐẦY ĐỦ 3 tham số (url, userId, folderId) xuống Background Worker
            jobScheduler.enqueue(() -> mediaEngineService.downloadYouTubeVideo(videoUrl, userId, folderId));

            return ResponseEntity.accepted().body(Map.of(
                    "message", "Yêu cầu tải video đã được đưa vào hàng đợi xử lý ngầm!",
                    "status", "Queued"
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
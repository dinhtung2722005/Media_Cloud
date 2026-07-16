package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaEngineService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/files") 
public class YouTubeController {

    private final JobScheduler jobScheduler;
    private final MediaEngineService mediaEngineService;

    public YouTubeController(JobScheduler jobScheduler, MediaEngineService mediaEngineService) {
        this.jobScheduler = jobScheduler;
        this.mediaEngineService = mediaEngineService;
    }

    private String getCurrentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @PostMapping("/youtube")
    public ResponseEntity<?> fetchYouTubeVideo(@RequestBody Map<String, String> request) {
        try {
            String videoUrl = request.get("url");
            String folderId = request.getOrDefault("folderId", ""); 
            
            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Link YouTube không được để trống!"));
            }
            
            // Lấy ID và Username của người đang gọi API
            String userId = getCurrentUserId();
            String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

            // Truyền ĐẦY ĐỦ 4 tham số xuống Background Worker để WebSocket hoạt động
            jobScheduler.enqueue(() -> 
                mediaEngineService.downloadYouTubeVideo(videoUrl, userId, folderId, currentUsername)
            );

            return ResponseEntity.accepted().body(Map.of(
                    "message", "Yêu cầu tải video đã được đưa vào hàng đợi xử lý ngầm!",
                    "status", "Queued"
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
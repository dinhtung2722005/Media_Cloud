package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.repository.MediaFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import jakarta.annotation.PostConstruct;

@Service
public class MediaEngineService {
    private static final Logger log = LoggerFactory.getLogger(MediaEngineService.class);
    private final MediaFileRepository files;
    private final FolderRepository folders;
    private final SimpMessagingTemplate messaging;
    private final FileService fileService;
    private final MediaProcessRunner processes;

    @Value("${app.storage.base-dir}") private String baseStorageDir;
    @Value("${app.tools.ffmpeg:ffmpeg}") private String ffmpegPath;
    @Value("${app.tools.yt-dlp:yt-dlp}") private String ytDlpPath;
    @Value("${app.jobs.timeout-seconds:1800}") private long timeoutSeconds = 1800;
    @Value("${app.jobs.youtube-max-bytes:1073741824}") private long youtubeMaxBytes = 1073741824L;

    public MediaEngineService(MediaFileRepository files, FolderRepository folders, SimpMessagingTemplate messaging,
                              FileService fileService, MediaProcessRunner processes) {
        this.files = files;
        this.folders = folders;
        this.messaging = messaging;
        this.fileService = fileService;
        this.processes = processes;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (timeoutSeconds < 1 || youtubeMaxBytes < 1) {
            throw new IllegalStateException("Thời gian chạy và giới hạn dung lượng tác vụ phải lớn hơn 0");
        }
    }

    public void convertToMp3(String fileId, String userId) {
        convertToMp3(fileId, userId, UUID.randomUUID().toString(), () -> false);
    }

    public void convertToMp3(String fileId, String userId, String resultId, BooleanSupplier cancelled) {
        Path output = null;
        boolean saved = false;
        String taskId = resultId;
        try {
            ensureNotCancelled(cancelled);
            if (alreadyCompleted(resultId, userId)) {
                publish(userId, taskId, "Chuyển MP3", "COMPLETED", Map.of("progress", 100));
                return;
            }
            MediaFile source = fileService.requireOwnedFile(fileId, userId);
            Path root = storageRoot();
            Path input = Path.of(source.getStoragePath()).toRealPath();
            if (!input.startsWith(root) || !Files.isRegularFile(input)) {
                throw new IllegalArgumentException("File phải nằm trong thư mục lưu trữ của ứng dụng");
            }
            output = root.resolve(resultId + ".mp3");
            cleanupOutput(output);
            List<String> command = List.of(ffmpegPath, "-nostdin", "-n", "-protocol_whitelist", "file,pipe",
                    "-i", input.toString(), "-vn", "-acodec", "libmp3lame", output.toString());
            int exit = processes.run(command, Duration.ofSeconds(timeoutSeconds), line -> {}, cancelled);
            if (exit != 0 || !Files.isRegularFile(output)) throw new IOException("FFmpeg chuyển đổi thất bại");
            ensureNotCancelled(cancelled);
            String title = source.getTitle();
            int dot = title.lastIndexOf('.');
            title = (dot > 0 ? title.substring(0, dot) : title) + ".mp3";
            files.saveAndFlush(new MediaFile(resultId, title, output.toString(), "mp3", Files.size(output),
                    source.getFolderId(), userId, "Ready", "Converted"));
            saved = true;
            publish(userId, taskId, "Chuyển MP3", "COMPLETED", Map.of("progress", 100));
        } catch (CancellationException exception) {
            publish(userId, taskId, "Chuyển MP3", "CANCELLED", Map.of("message", "Đã hủy tác vụ"));
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publish(userId, taskId, "Chuyển MP3", "CANCELLED", Map.of("message", "Đã hủy tác vụ"));
            throw new IllegalStateException("Đã hủy chuyển đổi", exception);
        } catch (Exception exception) {
            publish(userId, taskId, "Chuyển MP3", "FAILED", Map.of("message", "Không thể chuyển đổi file"));
            throw new IllegalStateException("Không thể chuyển đổi file", exception);
        } finally {
            if (!saved && output != null) cleanupOutput(output);
        }
    }

    public void downloadYouTubeVideo(String youtubeUrl, String userId, String folderId) {
        downloadYouTubeVideo(youtubeUrl, userId, folderId, UUID.randomUUID().toString(), () -> false);
    }

    public void downloadYouTubeVideo(String youtubeUrl, String userId, String folderId, String fileId, BooleanSupplier cancelled) {
        String taskId = fileId;
        Path output = null;
        boolean saved = false;
        try {
            ensureNotCancelled(cancelled);
            if (alreadyCompleted(fileId, userId)) {
                publish(userId, taskId, "Tải YouTube", "COMPLETED", Map.of("progress", 100));
                return;
            }
            String folder = folderId == null || folderId.isBlank() ? null : folderId;
            if (folder != null && folders.findById(folder).filter(item -> userId.equals(item.getUserId())).isEmpty()) {
                throw new IllegalArgumentException("Không có quyền truy cập thư mục");
            }
            output = storageRoot().resolve(fileId + ".mp4");
            cleanupOutput(output);
            List<String> command = new ArrayList<>(List.of(ytDlpPath, "--no-playlist", "--no-overwrites",
                    "--newline", "--max-filesize", Long.toString(youtubeMaxBytes),
                    "-f", "bestvideo+bestaudio/best", "--merge-output-format", "mp4"));
            if (Path.of(ffmpegPath).getParent() != null) {
                command.add("--ffmpeg-location");
                command.add(Path.of(ffmpegPath).toAbsolutePath().toString());
            }
            command.addAll(List.of("-o", output.toString(), "--", youtubeUrl));
            int exit = processes.run(command, Duration.ofSeconds(timeoutSeconds), line -> {
                if (line.contains("%")) publish(userId, taskId, "Tải YouTube", "PROCESSING", Map.of("progressLine", line));
            }, cancelled);
            if (exit != 0 || !Files.isRegularFile(output)) throw new IOException("yt-dlp tải thất bại");
            if (Files.size(output) > youtubeMaxBytes) throw new IOException("Video vượt giới hạn dung lượng");
            ensureNotCancelled(cancelled);
            files.saveAndFlush(new MediaFile(fileId, "YouTube Video - " + fileId.substring(0, 6), output.toString(),
                    "mp4", Files.size(output), folder, userId, "Ready", "YouTube"));
            saved = true;
            publish(userId, taskId, "Tải YouTube", "COMPLETED", Map.of("progress", 100));
        } catch (CancellationException exception) {
            publish(userId, taskId, "Tải YouTube", "CANCELLED", Map.of("message", "Đã hủy tác vụ"));
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publish(userId, taskId, "Tải YouTube", "CANCELLED", Map.of("message", "Đã hủy tác vụ"));
            throw new IllegalStateException("Đã hủy tải video", exception);
        } catch (Exception exception) {
            publish(userId, taskId, "Tải YouTube", "FAILED", Map.of("message", "Không thể tải video"));
            throw new IllegalStateException("Không thể tải video", exception);
        } finally {
            if (!saved && output != null) cleanupOutput(output);
        }
    }

    private void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Đã hủy tác vụ");
    }

    private boolean alreadyCompleted(String id, String userId) {
        UUID.fromString(id);
        return files.findById(id).map(file -> {
            if (!userId.equals(file.getUserId())) throw new IllegalArgumentException("Mã đầu ra đã được sử dụng");
            return true;
        }).orElse(false);
    }

    private Path storageRoot() throws IOException {
        Path root = Path.of(baseStorageDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root.toRealPath();
    }

    private void publish(String userId, String taskId, String name, String status, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("taskId", taskId);
        payload.put("taskName", name);
        payload.put("status", status);
        try {
            messaging.convertAndSendToUser(userId, "/queue/progress", payload);
        } catch (RuntimeException exception) {
            log.warn("Không thể gửi tiến độ cho tác vụ {}", taskId);
        }
    }

    private void cleanupOutput(Path output) {
        // Only remove files bearing this server-generated UUID; never follow directories.
        String prefix = output.getFileName().toString().substring(0, 36) + ".";
        try (var candidates = Files.list(output.getParent())) {
            for (Path candidate : candidates.filter(path -> path.getFileName().toString().startsWith(prefix)).toList()) {
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(candidate);
            }
        } catch (IOException exception) {
            log.warn("Không thể dọn đầu ra của tác vụ {}", prefix);
        }
    }
}

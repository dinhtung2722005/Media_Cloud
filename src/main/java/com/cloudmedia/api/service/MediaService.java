package com.cloudmedia.api.service;

import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.repository.FolderRepository;
import com.cloudmedia.api.repository.MediaFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final long CHUNK_SIZE = 5L * 1024 * 1024;
    private static final int MAX_CHUNKS = 10000;
    private final Object[] uploadLocks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final MediaFileRepository mediaFileRepository;
    private final FolderRepository folderRepository;

    @Value("${app.storage.temp-dir}")
    private String tempStorageDir;

    @Value("${app.storage.base-dir}")
    private String baseStorageDir;

    @Value("${app.upload.max-active-per-user:5}")
    private int maxActiveUploads = 5;
    @Value("${app.upload.max-temp-bytes-per-user:1073741824}")
    private long maxTempBytes = 1073741824L;
    @Value("${app.upload.expire-after-ms:86400000}")
    private long expireAfterMillis = 86400000L;

    public MediaService(MediaFileRepository mediaFileRepository, FolderRepository folderRepository) {
        this.mediaFileRepository = mediaFileRepository;
        this.folderRepository = folderRepository;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (maxActiveUploads < 1 || maxTempBytes < 1 || expireAfterMillis < 1) {
            throw new IllegalStateException("Giới hạn upload và thời hạn lưu phải lớn hơn 0");
        }
    }

    public void saveChunk(MultipartFile chunk, String fileGuid, int chunkIndex, int totalChunks, String userId)
            throws IOException {
        Path uploadDir = uploadDirectory(userId, fileGuid);
        validateChunkCount(totalChunks);
        if (chunkIndex < 0 || chunkIndex >= totalChunks || chunk.getSize() > CHUNK_SIZE
                || (chunk.isEmpty() && totalChunks != 1)) {
            throw new IllegalArgumentException("Mảnh file không hợp lệ hoặc vượt quá 5 MB");
        }
        synchronized (lockFor(uploadDir.getParent())) {
            Path ownerDir = uploadDir.getParent();
            Files.createDirectories(ownerDir);
            cleanupOwner(ownerDir, System.currentTimeMillis());
            enforceUploadLimits(ownerDir, uploadDir, chunkIndex, chunk.getSize());
            Files.createDirectories(uploadDir);
            Path manifest = uploadDir.resolve("total-chunks");
            if (Files.exists(manifest)) {
                requireChunkCount(manifest, totalChunks);
            } else {
                Files.writeString(manifest, Integer.toString(totalChunks));
            }
            Path partial = uploadDir.resolve(chunkIndex + ".part");
            try {
                try (InputStream input = chunk.getInputStream()) {
                    Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(partial, uploadDir.resolve(chunkIndex + ".tmp"), StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(uploadDir, FileTime.fromMillis(System.currentTimeMillis()));
            } finally {
                Files.deleteIfExists(partial);
            }
        }
    }

    public MediaFile mergeChunks(String fileGuid, String originalFilename, int totalChunks,
                                 String userId, String folderId) throws IOException {
        Path uploadDir = uploadDirectory(userId, fileGuid);
        validateChunkCount(totalChunks);
        String extension = fileExtension(originalFilename);
        String validFolderId = folderId == null || folderId.isBlank() ? null : folderId;
        if (validFolderId != null && folderRepository.findById(validFolderId)
                .filter(folder -> userId.equals(folder.getUserId())).isEmpty()) {
            throw new IllegalArgumentException("Không có quyền truy cập thư mục này");
        }

        synchronized (lockFor(uploadDir.getParent())) {
            requireChunkCount(uploadDir.resolve("total-chunks"), totalChunks);
            // Check every chunk before opening the final file. Failed merges can be retried.
            for (int i = 0; i < totalChunks; i++) {
                if (!Files.isRegularFile(uploadDir.resolve(i + ".tmp"))) {
                    throw new IllegalArgumentException("Thiếu mảnh file thứ " + i);
                }
            }
            String fileId = UUID.randomUUID().toString();
            Path baseDir = Path.of(baseStorageDir).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            Path finalPath = baseDir.resolve(fileId + (extension.isEmpty() ? "" : "." + extension));
            MediaFile saved;
            try {
                long size = 0;
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(finalPath))) {
                    for (int i = 0; i < totalChunks; i++) {
                        try (InputStream input = Files.newInputStream(uploadDir.resolve(i + ".tmp"))) {
                            size += input.transferTo(output);
                        }
                    }
                }
                MediaFile file = new MediaFile(fileId, originalFilename, finalPath.toString(), extension,
                        size, validFolderId, userId, "Ready", "Local_Upload");
                // The repository transaction commits before temporary chunks are removed.
                saved = mediaFileRepository.saveAndFlush(file);
            } catch (IOException | RuntimeException exception) {
                try {
                    Files.deleteIfExists(finalPath);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
            try {
                for (int i = 0; i < totalChunks; i++) Files.deleteIfExists(uploadDir.resolve(i + ".tmp"));
                Files.deleteIfExists(uploadDir.resolve("total-chunks"));
                Files.deleteIfExists(uploadDir);
            } catch (IOException exception) {
                log.warn("Không thể dọn các mảnh tạm của upload {}", fileGuid, exception);
            }
            return saved;
        }
    }

    @Scheduled(fixedDelayString = "${app.upload.cleanup-interval-ms:3600000}")
    public void cleanupExpiredUploads() {
        Path root = Path.of(tempStorageDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var owners = Files.list(root)) {
            for (Path owner : owners.filter(this::isUuidDirectory).toList()) {
                synchronized (lockFor(owner)) {
                    try { cleanupOwner(owner, System.currentTimeMillis()); }
                    catch (IOException exception) { log.warn("Không thể dọn upload tạm của {}", owner.getFileName()); }
                }
            }
        } catch (IOException exception) {
            log.warn("Không thể đọc thư mục upload tạm");
        }
    }

    private void enforceUploadLimits(Path owner, Path upload, int chunkIndex, long newSize) throws IOException {
        if (!Files.exists(upload)) {
            try (var active = Files.list(owner)) {
                if (active.filter(this::isUuidDirectory).count() >= maxActiveUploads) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đã đạt giới hạn upload đang mở");
                }
            }
        }
        long used = 0;
        try (var paths = Files.walk(owner)) {
            for (Path path : paths.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp") || name.endsWith(".part")) used += Files.size(path);
            }
        }
        Path previous = upload.resolve(chunkIndex + ".tmp");
        long replaced = Files.isRegularFile(previous) ? Files.size(previous) : 0;
        if (newSize > maxTempBytes - (used - replaced)) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Vượt giới hạn dung lượng upload tạm");
        }
    }

    private void cleanupOwner(Path owner, long now) throws IOException {
        try (var uploads = Files.list(owner)) {
            for (Path upload : uploads.filter(this::isUuidDirectory).toList()) {
                if (now - Files.getLastModifiedTime(upload).toMillis() < expireAfterMillis) continue;
                Path root = Path.of(tempStorageDir).toAbsolutePath().normalize();
                Path target = upload.toAbsolutePath().normalize();
                if (!target.startsWith(root) || target.getNameCount() != root.getNameCount() + 2) {
                    throw new IOException("Đường dẫn upload tạm không hợp lệ");
                }
                // Files.walk does not follow symbolic links. Only validated UUID upload directories are removed.
                try (var paths = Files.walk(target)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private boolean isUuidDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try { return UUID.fromString(path.getFileName().toString()).toString().equals(path.getFileName().toString()); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private Path uploadDirectory(String userId, String fileGuid) {
        try {
            String owner = UUID.fromString(userId).toString();
            String upload = UUID.fromString(fileGuid).toString();
            if (!owner.equals(userId) || !upload.equals(fileGuid)) throw new IllegalArgumentException();
            return Path.of(tempStorageDir).toAbsolutePath().normalize().resolve(owner).resolve(upload);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Mã người dùng hoặc mã upload không hợp lệ");
        }
    }

    private Object lockFor(Path uploadDir) {
        return uploadLocks[Math.floorMod(uploadDir.hashCode(), uploadLocks.length)];
    }

    private void validateChunkCount(int totalChunks) {
        if (totalChunks < 1 || totalChunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("Số lượng mảnh file không hợp lệ");
        }
    }

    private void requireChunkCount(Path manifest, int totalChunks) throws IOException {
        if (!Files.isRegularFile(manifest)
                || !Integer.toString(totalChunks).equals(Files.readString(manifest))) {
            throw new IllegalArgumentException("Không tìm thấy upload hoặc số lượng mảnh không khớp");
        }
    }

    static String fileExtension(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Tên file không hợp lệ");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot + 1);
        if (!extension.matches("[a-zA-Z0-9]{1,10}")) {
            throw new IllegalArgumentException("Phần mở rộng file không hợp lệ");
        }
        return extension;
    }
}

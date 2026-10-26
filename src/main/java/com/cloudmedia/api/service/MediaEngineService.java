package com.cloudmedia.api.service;

import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.FolderRepository;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaEngineService {
  public record Prepared(MediaFile file, Path output, FileStorage storage)
      implements AutoCloseable {
    public void close() {
      storage.cleanup(output);
    }
  }

  private final FileService files;
  private final FolderRepository folders;
  private final MediaProcessRunner processes;
  private final FileStorage storage;

  @Value("${app.tools.ffmpeg:ffmpeg}")
  private String ffmpegPath = "ffmpeg";

  @Value("${app.tools.yt-dlp:yt-dlp}")
  private String ytDlpPath = "yt-dlp";

  @Value("${app.jobs.timeout-seconds:1800}")
  private long timeoutSeconds = 1800;

  @Value("${app.jobs.output-max-bytes:1073741824}")
  private long outputMaxBytes = 1073741824L;

  @Value("${app.jobs.youtube-max-bytes:1073741824}")
  private long youtubeMaxBytes = 1073741824L;

  public MediaEngineService(
      FileService files,
      FolderRepository folders,
      MediaProcessRunner processes,
      FileStorage storage) {
    this.files = files;
    this.folders = folders;
    this.processes = processes;
    this.storage = storage;
  }

  public Prepared prepare(
      MediaJob job, String attempt, BooleanSupplier stopped, IntConsumer progress) {
    Path output = null;
    try {
      check(stopped);
      if (job.getFolderId() != null)
        folders
            .findById(job.getFolderId())
            .filter(f -> job.getUserId().equals(f.getUserId()))
            .orElseThrow(DomainException::missing);
      String extension = job.getType() == MediaJob.Type.CONVERT_MP3 ? "mp3" : "mp4";
      output = storage.allocate(attempt, extension);
      String title;
      List<String> command;
      if (job.getType() == MediaJob.Type.CONVERT_MP3) {
        MediaFile source = files.requireOwnedFile(job.getFileId(), job.getUserId());
        Path input = storage.resolve(source.getStoragePath());
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) throw DomainException.missing();
        title = source.getTitle();
        int dot = title.lastIndexOf('.');
        title = (dot > 0 ? title.substring(0, dot) : title) + ".mp3";
        command =
            List.of(
                ffmpegPath,
                "-nostdin",
                "-n",
                "-protocol_whitelist",
                "file,pipe",
                "-i",
                input.toString(),
                "-vn",
                "-acodec",
                "libmp3lame",
                output.toString());
      } else {
        title = "YouTube Video - " + job.getId().substring(0, 8) + ".mp4";
        command =
            new ArrayList<>(
                List.of(
                    ytDlpPath,
                    "--no-playlist",
                    "--no-overwrites",
                    "--newline",
                    "--max-filesize",
                    Long.toString(Math.min(outputMaxBytes, youtubeMaxBytes)),
                    "-f",
                    "bestvideo+bestaudio/best",
                    "--merge-output-format",
                    "mp4"));
        if (Path.of(ffmpegPath).getParent() != null)
          command.addAll(
              List.of("--ffmpeg-location", Path.of(ffmpegPath).toAbsolutePath().toString()));
        command.addAll(List.of("-o", output.toString(), "--", job.getUrl()));
      }
      java.util.concurrent.atomic.AtomicInteger last =
          new java.util.concurrent.atomic.AtomicInteger(-1);
      int exit =
          processes.run(
              command,
              Duration.ofSeconds(timeoutSeconds),
              line -> {
                if (job.getType() == MediaJob.Type.YOUTUBE) {
                  var match = java.util.regex.Pattern.compile("([0-9.]+)%").matcher(line);
                  if (match.find())
                    try {
                      int value = (int) Double.parseDouble(match.group(1));
                      if (value != last.getAndSet(value)) progress.accept(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
              },
              stopped);
      check(stopped);
      if (exit != 0 || !Files.isRegularFile(output)) throw new IOException("Media tool failed");
      long size = Files.size(output),
          limit =
              job.getType() == MediaJob.Type.YOUTUBE
                  ? Math.min(outputMaxBytes, youtubeMaxBytes)
                  : outputMaxBytes;
      if (size > limit) throw DomainException.invalid("Kết quả vượt giới hạn dung lượng");
      return new Prepared(
          new MediaFile(
              job.getId(),
              title,
              storage.key(output),
              extension,
              size,
              job.getFolderId(),
              job.getUserId(),
              "Ready",
              job.getType() == MediaJob.Type.YOUTUBE ? "YouTube" : "Converted"),
          output,
          storage);
    } catch (RuntimeException error) {
      storage.cleanup(output);
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      storage.cleanup(output);
      throw new CancellationException("Worker interrupted");
    } catch (IOException error) {
      storage.cleanup(output);
      throw new IllegalStateException("Không thể xử lý media", error);
    }
  }

  private void check(BooleanSupplier stopped) {
    if (stopped.getAsBoolean())
      throw new CancellationException("Lượt xử lý đã bị hủy hoặc mất quyền chạy");
  }
}

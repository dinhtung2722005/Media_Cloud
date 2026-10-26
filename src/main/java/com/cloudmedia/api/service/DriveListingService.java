package com.cloudmedia.api.service;

import com.cloudmedia.api.cache.*;
import com.cloudmedia.api.controller.ApiViews;
import com.cloudmedia.api.domain.*;
import com.cloudmedia.api.entity.*;
import com.cloudmedia.api.repository.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class DriveListingService {
  private static final Map<String, List<String>> TYPES =
      Map.of(
          "video",
          List.of("mp4", "webm", "mov", "mkv", "m4v", "avi", "ogv"),
          "image",
          List.of("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "svg"),
          "audio",
          List.of("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus"));
  private final FolderService folders;
  private final FolderRepository folderRows;
  private final MediaFileRepository files;
  private final DriveCache cache;

  @Autowired
  public DriveListingService(
      FolderService folders,
      FolderRepository folderRows,
      MediaFileRepository files,
      DriveCache cache) {
    this.folders = folders;
    this.folderRows = folderRows;
    this.files = files;
    this.cache = cache;
  }

  public Map<String, Object> list(
      String user, String folder, int page, int size, String query, String sort, String type) {
    if (page < 0
        || page > 100000
        || size < 1
        || size > 100
        || query == null
        || query.length() > 255
        || !Set.of("name", "name-desc", "size-asc", "size-desc").contains(sort)
        || !Set.of("all", "video", "image", "audio", "document").contains(type))
      throw DomainException.invalid("Bộ lọc hoặc phân trang không hợp lệ");
    String parent = normalize(folder), q = InputRules.normalized(query.trim());
    Folder info =
        parent == null
            ? null
            : folders.getFolderById(parent, user); // Fresh ownership even on a cache hit.
    String variant =
        "page2:" + parent + ":" + page + ":" + size + ":" + sort + ":" + type + ":" + q;
    DriveContents data =
        cache.get(user, variant, () -> read(user, parent, page, size, q, sort, type));
    return response(info, data, page, size);
  }

  private DriveContents read(
      String user, String parent, int page, int size, String q, String sort, String type) {
    String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    Specification<Folder> folderSpec =
        (r, query, b) ->
            b.and(
                b.equal(r.get("userId"), user),
                parent == null
                    ? b.isNull(r.get("parentFolderId"))
                    : b.equal(r.get("parentFolderId"), parent),
                b.like(b.coalesce(r.get("searchName"), b.lower(r.get("folderName"))), like, '\\'));
    Specification<MediaFile> fileSpec =
        (r, query, b) -> {
          var base =
              b.and(
                  b.equal(r.get("userId"), user),
                  b.equal(r.get("status"), "Ready"),
                  parent == null ? b.isNull(r.get("folderId")) : b.equal(r.get("folderId"), parent),
                  b.like(b.coalesce(r.get("searchTitle"), b.lower(r.get("title"))), like, '\\'));
          if (TYPES.containsKey(type))
            return b.and(base, b.lower(r.get("extension")).in(TYPES.get(type)));
          if ("document".equals(type))
            return b.and(
                base,
                b.not(
                    b.lower(r.get("extension"))
                        .in(TYPES.values().stream().flatMap(Collection::stream).toList())));
          return base;
        };
    boolean desc = sort.endsWith("desc");
    Sort.Direction direction = desc ? Sort.Direction.DESC : Sort.Direction.ASC;
    Page<Folder> folderPage =
        "all".equals(type)
            ? folderRows.findAll(
                folderSpec,
                PageRequest.of(page, size, Sort.by(direction, "folderName").and(Sort.by("id"))))
            : Page.empty();
    Page<MediaFile> filePage =
        files.findAll(
            fileSpec,
            PageRequest.of(
                page,
                size,
                Sort.by(direction, sort.startsWith("size") ? "fileSize" : "title")
                    .and(Sort.by("id"))));
    Map<String, Long> counts = new HashMap<>(), bytes = new HashMap<>();
    for (Object[] row : files.overview(user, parent)) {
      String category = category(String.valueOf(row[0]));
      counts.merge(category, ((Number) row[1]).longValue(), Long::sum);
      bytes.merge(category, ((Number) row[2]).longValue(), Long::sum);
    }
    return new DriveContents(
        folderPage.getContent(),
        filePage.getContent(),
        folderPage.getTotalElements(),
        filePage.getTotalElements(),
        counts,
        bytes);
  }

  private Map<String, Object> response(Folder info, DriveContents data, int page, int size) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (info != null) result.put("folderInfo", ApiViews.folder(info));
    result.put(
        info == null ? "folders" : "subFolders",
        data.folders().stream().map(ApiViews::folder).toList());
    result.put("files", data.files().stream().map(ApiViews::file).toList());
    result.put("page", page);
    result.put("size", size);
    result.put("totalFolders", data.totalFolders());
    result.put("totalFiles", data.totalFiles());
    result.put(
        "hasMore", ((long) page + 1) * size < Math.max(data.totalFolders(), data.totalFiles()));
    result.put("counts", data.counts());
    result.put("bytes", data.bytes());
    return result;
  }

  private static String normalize(String id) {
    return id == null || id.isBlank() ? null : id;
  }

  private static String category(String extension) {
    return TYPES.entrySet().stream()
        .filter(e -> e.getValue().contains(extension))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("document");
  }
}

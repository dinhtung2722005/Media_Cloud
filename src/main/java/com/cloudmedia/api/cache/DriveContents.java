package com.cloudmedia.api.cache;

import com.cloudmedia.api.entity.Folder;
import com.cloudmedia.api.entity.MediaFile;
import java.util.List;

public record DriveContents(
    List<Folder> folders,
    List<MediaFile> files,
    long totalFolders,
    long totalFiles,
    java.util.Map<String, Long> counts,
    java.util.Map<String, Long> bytes) {
  public DriveContents(List<Folder> folders, List<MediaFile> files) {
    this(folders, files, folders.size(), files.size(), java.util.Map.of(), java.util.Map.of());
  }

  public DriveContents {
    folders = List.copyOf(folders);
    files = List.copyOf(files);
    counts = counts == null ? java.util.Map.of() : java.util.Map.copyOf(counts);
    bytes = bytes == null ? java.util.Map.of() : java.util.Map.copyOf(bytes);
  }
}

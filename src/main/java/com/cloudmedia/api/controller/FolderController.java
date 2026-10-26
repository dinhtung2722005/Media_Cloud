package com.cloudmedia.api.controller;

import com.cloudmedia.api.domain.InputRules;
import com.cloudmedia.api.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
  private final FolderService folders;
  private final DriveListingService listings;

  public FolderController(FolderService folders, DriveListingService listings) {
    this.folders = folders;
    this.listings = listings;
  }

  private String user() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  public record FolderRequest(String folderName, String parentId) {
    public FolderRequest {
      folderName = InputRules.name(folderName);
    }
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestBody FolderRequest request) {
    var folder = folders.createFolder(request.folderName(), request.parentId(), user());
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(folder.getId())
                .toUri())
        .body(ApiViews.folder(folder));
  }

  @GetMapping
  public Object list(
      @RequestParam(required = false) String parentId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "all") String type) {
    return listings.list(user(), parentId, page, size, q, sort, type);
  }

  @GetMapping("/{id}")
  public Object get(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "all") String type) {
    return listings.list(user(), id, page, size, q, sort, type);
  }

  @PutMapping("/{id}")
  public Object rename(@PathVariable String id, @RequestBody FolderRequest request) {
    return ApiViews.folder(folders.updateFolderName(id, request.folderName(), user()));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    folders.deleteFolder(id, user());
    return ResponseEntity.noContent().build();
  }
}

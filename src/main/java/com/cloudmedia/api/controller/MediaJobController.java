package com.cloudmedia.api.controller;

import com.cloudmedia.api.service.MediaJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media/jobs")
public class MediaJobController {
  private final MediaJobService jobs;

  public MediaJobController(MediaJobService jobs) {
    this.jobs = jobs;
  }

  @GetMapping("/{id}")
  public ApiViews.JobView status(@PathVariable String id) {
    return ApiViews.JobView.from(jobs.requireOwned(id, user()));
  }

  @GetMapping
  public Object list(
      @RequestParam(defaultValue = "true") boolean active,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var result = jobs.list(user(), active, page, size);
    return java.util.Map.of(
        "jobs",
        result.getContent().stream().map(ApiViews.JobView::from).toList(),
        "page",
        page,
        "hasMore",
        result.hasNext(),
        "total",
        result.getTotalElements());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> cancel(@PathVariable String id) {
    jobs.cancel(id, user());
    return ResponseEntity.accepted().body(ApiViews.JobView.from(jobs.requireOwned(id, user())));
  }

  private String user() {
    return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}

package com.cloudmedia.api.controller;

import com.cloudmedia.api.messaging.JobSubmissionException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@org.springframework.core.annotation.Order(0)
public class JobSubmissionExceptionHandler {
  @ExceptionHandler(JobSubmissionException.class)
  public ResponseEntity<?> unavailable(JobSubmissionException exception) {
    return ResponseEntity.status(503)
        .body(Map.of("error", exception.getMessage(), "jobId", exception.getJobId()));
  }
}

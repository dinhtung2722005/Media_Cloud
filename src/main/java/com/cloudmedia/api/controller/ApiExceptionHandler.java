package com.cloudmedia.api.controller;

import com.cloudmedia.api.domain.DomainException;
import java.io.*;
import java.util.Map;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Order(100)
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<?> domain(DomainException error) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
    if (error.status() == HttpStatus.TOO_MANY_REQUESTS)
      response.header(HttpHeaders.RETRY_AFTER, "60");
    return response.body(Map.of("code", error.code(), "error", error.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception error) {
    return response(400, "INVALID_INPUT", "Thông tin yêu cầu không hợp lệ");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> conflict(Exception error) {
    return response(
        409, "DATA_CONFLICT", "Dữ liệu đã thay đổi hoặc đang được sử dụng. Vui lòng tải lại");
  }

  @ExceptionHandler({DataAccessException.class, IOException.class, UncheckedIOException.class})
  public ResponseEntity<?> unavailable(Exception error) {
    return response(
        503, "TEMPORARILY_UNAVAILABLE", "Dịch vụ tạm thời chưa sẵn sàng. Vui lòng thử lại");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException error) {
    return response(
        error.getStatusCode().value(),
        "REQUEST_FAILED",
        error.getReason() == null ? "Không thể hoàn tất yêu cầu" : error.getReason());
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missingResource(Exception error) {
    return response(404, "NOT_FOUND", "Không tìm thấy tài nguyên");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception error) {
    log.error("Unhandled request failure ({})", error.getClass().getSimpleName());
    return response(500, "INTERNAL_ERROR", "Không thể hoàn tất yêu cầu. Vui lòng thử lại");
  }

  private ResponseEntity<?> response(int status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "error", message));
  }
}

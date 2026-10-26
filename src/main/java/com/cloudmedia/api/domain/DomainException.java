package com.cloudmedia.api.domain;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public DomainException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static DomainException invalid(String message) {
    return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
  }

  public static DomainException missing() {
    return new DomainException(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "Không tìm thấy dữ liệu hoặc bạn không có quyền truy cập");
  }

  public static DomainException conflict(String code, String message) {
    return new DomainException(HttpStatus.CONFLICT, code, message);
  }
}

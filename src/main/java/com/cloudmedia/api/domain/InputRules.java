package com.cloudmedia.api.domain;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

public final class InputRules {
  private InputRules() {}

  public static String username(String value) {
    if (value == null || value.isBlank())
      throw DomainException.invalid("Vui lòng nhập tên đăng nhập");
    String result = value.trim();
    if (result.length() > 50 || result.chars().anyMatch(Character::isISOControl))
      throw DomainException.invalid("Tên đăng nhập tối đa 50 ký tự");
    return result;
  }

  public static String password(String value, boolean registration) {
    if (value == null
        || value.isEmpty()
        || (registration && value.length() < 8)
        || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw DomainException.invalid(
          registration
              ? "Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8"
              : "Mật khẩu không hợp lệ");
    return value;
  }

  public static String name(String value) {
    if (value == null || value.isBlank()) throw DomainException.invalid("Tên không được để trống");
    String result = value.trim();
    if (result.length() > 255
        || result.equals(".")
        || result.equals("..")
        || result.contains("/")
        || result.contains("\\")
        || result.chars().anyMatch(Character::isISOControl))
      throw DomainException.invalid(
          "Tên tối đa 255 ký tự, không chứa dấu phân cách đường dẫn hay ký tự điều khiển");
    return result;
  }

  public static String uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
      return value;
    } catch (RuntimeException e) {
      throw DomainException.invalid("Mã định danh không hợp lệ");
    }
  }

  public static String key(String value) {
    if (value == null || value.isBlank()) return null;
    if (value.length() > 100 || !value.matches("[A-Za-z0-9._:-]+"))
      throw DomainException.invalid("Idempotency-Key không hợp lệ");
    return value;
  }

  public static String normalized(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("[\\p{M}]", "")
        .toLowerCase(Locale.ROOT)
        .replace('đ', 'd');
  }
}

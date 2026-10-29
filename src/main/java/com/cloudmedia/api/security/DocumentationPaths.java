package com.cloudmedia.api.security;

import jakarta.servlet.http.HttpServletRequest;

/** Shared allowlist for the documentation UI and its local assets. */
public final class DocumentationPaths {
  private DocumentationPaths() {}

  public static boolean isPublicRead(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return false;
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals("/swagger-ui.html")
        || path.startsWith("/swagger-ui/")
        || path.equals("/v3/api-docs")
        || path.startsWith("/v3/api-docs/")
        || path.equals("/v3/api-docs.yaml");
  }
}

package com.cloudmedia.api.security;

import com.cloudmedia.api.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtUtils jwtUtils;
  private final AuthSessionService sessions;

  public JwtAuthenticationFilter(JwtUtils jwtUtils, AuthSessionService sessions) {
    this.sessions = sessions;
    this.jwtUtils = jwtUtils;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    return path.startsWith("/api/auth/")
        || read
            && (path.startsWith("/api/public/shares/")
                || path.startsWith("/s/")
                || path.equals("/share.html")
                || path.equals("/share.js"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      // Cookie cho trình duyệt, Bearer header cho API client.
      String jwt = getJwtFromRequest(request);

      if (jwt != null && jwtUtils.validateAccessToken(jwt)) {
        if (!sessions.isActive(jwt)) {
          System.out.println("CẢNH BÁO: Phát hiện Token đã bị thu hồi cố gắng truy cập!");
          response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token đã bị thu hồi!");
          return; // Chặn đứng luôn, không cho đi tiếp
        }
        // Bóc tách UserId trực tiếp từ Token để ngăn chặn IDOR
        String userId = jwtUtils.getUserIdFromJwtToken(jwt);

        // Lưu thông tin xác thực vào Context của Spring Security
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());
        authentication.setDetails(jwtUtils.getSessionId(jwt));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    } catch (Exception e) {
      SecurityContextHolder.clearContext();
      response.sendError(
          HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Không thể kiểm tra phiên đăng nhập");
      return;
    }

    filterChain.doFilter(request, response);
  }

  // HÀM DUY NHẤT LÀM NHIỆM VỤ TÌM TOKEN (Ưu tiên Cookie trước, Header sau)
  private String getJwtFromRequest(HttpServletRequest request) {
    // 1. Tìm trong Cookie
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if ("access_token".equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }

    // 2. Tìm trong Header (Dùng cho Postman)
    String bearerToken = request.getHeader("Authorization");
    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7);
    }

    return null;
  }
}

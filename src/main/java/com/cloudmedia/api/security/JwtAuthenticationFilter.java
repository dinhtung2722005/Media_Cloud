package com.cloudmedia.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    public JwtAuthenticationFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // ĐÃ SỬA: Gọi đúng hàm getJwtFromRequest để bóc Token từ Cookie
            String jwt = getJwtFromRequest(request);
            
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                // Bóc tách UserId trực tiếp từ Token để ngăn chặn IDOR
                String userId = jwtUtils.getUserIdFromJwtToken(jwt);

                // Lưu thông tin xác thực vào Context của Spring Security
                UsernamePasswordAuthenticationToken authentication = 
                        new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            System.err.println("Không thể thiết lập xác thực người dùng: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    // HÀM DUY NHẤT LÀM NHIỆM VỤ TÌM TOKEN (Ưu tiên Cookie trước, Header sau)
    private String getJwtFromRequest(HttpServletRequest request) {
        // 1. Tìm trong Cookie
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("jwt_token".equals(cookie.getName())) {
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
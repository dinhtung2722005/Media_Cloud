package com.cloudmedia.api.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  @Value("${app.docs.enabled:true}")
  private boolean documentationEnabled;

  public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // Tắt CSRF vì chúng ta dùng JWT
        .csrf(csrf -> csrf.disable())
        // Thiết lập cơ chế không lưu trạng thái (Stateless)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, exception) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        request -> documentationEnabled && DocumentationPaths.isPublicRead(request))
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/s/*",
                        "/share.html",
                        "/share.js",
                        "/api/public/shares/*",
                        "/api/public/shares/*/stream",
                        "/api/public/shares/*/download")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.HEAD,
                        "/s/*",
                        "/share.html",
                        "/share.js",
                        "/api/public/shares/*",
                        "/api/public/shares/*/stream",
                        "/api/public/shares/*/download")
                    .permitAll()
                    // Mở cửa cho các API xác thực ban đầu
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    .requestMatchers("/", "/index.html", "/app.js", "/css/**", "/js/**")
                    .permitAll()
                    // Tất cả các API quản lý thư mục, file đều phải được xác thực
                    .anyRequest()
                    .authenticated());

    // Chèn bộ lọc JWT của chúng ta vào trước bộ lọc mặc định của Spring
    http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}

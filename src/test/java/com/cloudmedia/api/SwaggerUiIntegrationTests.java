package com.cloudmedia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmedia.api.messaging.JobOutboxDispatcher;
import com.cloudmedia.api.messaging.MediaJobPublisher;
import com.cloudmedia.api.security.AuthRateLimiter;
import com.cloudmedia.api.service.StorageMaintenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "spring.rabbitmq.username=test",
      "spring.rabbitmq.password=test",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "app.events.redis-enabled=false",
      "app.cache.enabled=false"
    })
@AutoConfigureMockMvc
abstract class SwaggerTestSupport {
  @DynamicPropertySource
  static void isolatedStorage(DynamicPropertyRegistry properties) {
    String id = UUID.randomUUID().toString();
    Path root = Path.of("target", "swagger-tests", id).toAbsolutePath();
    properties.add("spring.datasource.url", () -> "jdbc:h2:mem:swagger-" + id);
    properties.add("app.storage.base-dir", () -> root.resolve("media").toString());
    properties.add("app.storage.temp-dir", () -> root.resolve("temp").toString());
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockBean AuthRateLimiter rateLimiter;
  @MockBean MediaJobPublisher publisher;
  @MockBean JobOutboxDispatcher dispatcher;
  @MockBean StorageMaintenance maintenance;

  Cookie login() throws Exception {
    String credentials =
        "{\"username\":\"swagger"
            + UUID.randomUUID().toString().replace("-", "")
            + "\",\"password\":\"password123\"}";
    mvc.perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
        .andExpect(status().isOk());
    return mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getCookie("access_token");
  }
}

class SwaggerUiIntegrationTests extends SwaggerTestSupport {
  @Test
  void documentationAndUiAssetsArePublicEvenWithRevokedLoginCookie() throws Exception {
    Cookie revoked = login();
    mvc.perform(post("/api/auth/logout").cookie(revoked)).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui.html").cookie(revoked))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));
    mvc.perform(get("/swagger-ui/index.html").cookie(revoked))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
    for (String asset :
        new String[] {"swagger-ui.css", "swagger-ui-bundle.js", "swagger-initializer.js"}) {
      mvc.perform(get("/swagger-ui/" + asset).cookie(revoked)).andExpect(status().isOk());
    }
    mvc.perform(get("/v3/api-docs/swagger-config").cookie(revoked))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/v3/api-docs"))
        .andExpect(jsonPath("$.withCredentials").value(true));
    mvc.perform(get("/v3/api-docs.yaml").cookie(revoked)).andExpect(status().isOk());
    mvc.perform(head("/v3/api-docs")).andExpect(status().isOk());
    mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/folders").cookie(revoked)).andExpect(status().isUnauthorized());
  }

  @Test
  void generatedContractDescribesRealAuthBoundariesAndMultipartUpload() throws Exception {
    var document =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(document.at("/info/title").asText()).isEqualTo("Media Cloud API");
    assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText())
        .isEqualTo("bearer");
    assertThat(document.at("/components/securitySchemes/cookieAuth/name").asText())
        .isEqualTo("access_token");
    assertThat(document.path("security")).hasSize(2);
    var paths = document.path("paths");
    assertThat(paths.path("/api/auth/login").path("post").path("security")).isEmpty();
    assertThat(paths.path("/api/public/shares/{token}").path("get").path("security")).isEmpty();
    assertThat(paths.path("/api/files/{fileId}/shares").path("post").has("security")).isFalse();
    assertThat(paths.has("/api/media/jobs")).isTrue();
    assertThat(paths.has("/s/{token}")).isFalse();
    for (String upload : new String[] {"/api/files/upload", "/api/files/upload-chunk"}) {
      var body = paths.path(upload).path("post").path("requestBody").path("content");
      assertThat(body.has("multipart/form-data")).isTrue();
      assertThat(
              body.path("multipart/form-data")
                  .path("schema")
                  .path("properties")
                  .path("file")
                  .path("format")
                  .asText())
          .isEqualTo("binary");
    }
    var loginSchema = document.at("/components/schemas/LoginRequest/properties");
    assertThat(loginSchema.has("username")).isTrue();
    assertThat(loginSchema.has("password")).isTrue();
  }

  @Test
  void loginCookieCanCallProtectedApiWithoutMakingThatApiPublic() throws Exception {
    mvc.perform(get("/api/folders")).andExpect(status().isUnauthorized());
    Cookie cookie = login();
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    mvc.perform(get("/api/folders").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/api/folders").header("Authorization", "Bearer " + cookie.getValue()))
        .andExpect(status().isOk());
  }
}

@TestPropertySource(properties = "SWAGGER_ENABLED=false")
class SwaggerUiDisabledIntegrationTests extends SwaggerTestSupport {
  @Test
  void disabledDocumentationHasNoHandlersAndNoAnonymousAllowlist() throws Exception {
    Cookie cookie = login();
    for (String path :
        new String[] {
          "/v3/api-docs",
          "/v3/api-docs/swagger-config",
          "/v3/api-docs.yaml",
          "/swagger-ui.html",
          "/swagger-ui/index.html"
        }) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).cookie(cookie))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    mvc.perform(get("/api/folders").cookie(cookie)).andExpect(status().isOk());
  }
}

package com.cloudmedia.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmedia.api.entity.FileShare;
import com.cloudmedia.api.messaging.*;
import com.cloudmedia.api.repository.FileShareRepository;
import com.cloudmedia.api.security.AuthRateLimiter;
import com.cloudmedia.api.service.StorageMaintenance;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:share-http;DB_CLOSE_DELAY=-1",
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
class ShareHttpIntegrationTests {
  static final Path root =
      Path.of("target", "share-http-tests", UUID.randomUUID().toString()).toAbsolutePath();

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry r) {
    r.add("app.storage.base-dir", () -> root.resolve("media").toString());
    r.add("app.storage.temp-dir", () -> root.resolve("temp").toString());
  }

  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @Autowired FileShareRepository shares;
  @MockBean AuthRateLimiter limiter;
  @MockBean MediaJobPublisher publisher;
  @MockBean JobOutboxDispatcher dispatcher;
  @MockBean StorageMaintenance maintenance;

  Cookie login(String name) throws Exception {
    String body = "{\"username\":\"" + name + "\",\"password\":\"password123\"}";
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    return mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getCookie("access_token");
  }

  @Test
  void publicShareSupportsRangeRevocationExpiryAndOwnerBoundaries() throws Exception {
    Cookie owner = login("owner" + UUID.randomUUID().toString().substring(0, 8)),
        other = login("other" + UUID.randomUUID().toString().substring(0, 8));
    String file =
        mapper
            .readTree(
                mvc.perform(
                        multipart("/api/files/upload")
                            .file(
                                new MockMultipartFile(
                                    "file", "video.mp4", "video/mp4", "sample-video".getBytes()))
                            .cookie(owner))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    mvc.perform(
            post("/api/files/" + file + "/shares")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/files/" + file + "/shares")
                .cookie(other)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
    var created =
        mapper.readTree(
            mvc.perform(
                    post("/api/files/" + file + "/shares")
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":24}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String token = created.get("path").asText().substring(3),
        id = created.get("id").asText(),
        api = "/api/public/shares/" + token;
    mvc.perform(get(created.get("path").asText()))
        .andExpect(status().isOk())
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(
            header()
                .string(
                    "Content-Security-Policy",
                    org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    mvc.perform(get(api))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("video.mp4"))
        .andExpect(jsonPath("$.storagePath").doesNotExist())
        .andExpect(jsonPath("$.userId").doesNotExist());
    mvc.perform(get(api + "/stream").header("Range", "bytes=0-5"))
        .andExpect(status().isPartialContent())
        .andExpect(content().bytes("sample".getBytes()))
        .andExpect(header().string("Content-Range", "bytes 0-5/12"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(head(api + "/stream"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Length", "12"));
    mvc.perform(get(api + "/download"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
    mvc.perform(delete("/api/files/" + file + "/shares/" + id).cookie(other))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/auth/logout").cookie(other)).andExpect(status().isOk());
    mvc.perform(get(api + "/stream").cookie(other))
        .andExpect(status().isOk()); // Stale login cookies must not block capability links.
    mvc.perform(delete("/api/files/" + file + "/shares/" + id).cookie(owner))
        .andExpect(status().isNoContent());
    mvc.perform(get(api)).andExpect(status().isNotFound());
    mvc.perform(get(api + "/stream")).andExpect(status().isNotFound());
    mvc.perform(get(api + "/download")).andExpect(status().isNotFound());
    var next =
        mapper.readTree(
            mvc.perform(
                    post("/api/files/" + file + "/shares")
                        .cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    FileShare expired = shares.findById(next.get("id").asText()).orElseThrow();
    ReflectionTestUtils.setField(expired, "expiresAt", Instant.now().minusSeconds(1));
    shares.saveAndFlush(expired);
    mvc.perform(get("/api/public/shares/" + next.get("path").asText().substring(3)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/files/stream/" + file)).andExpect(status().isUnauthorized());
    mvc.perform(post(api + "/stream")).andExpect(status().isUnauthorized());
  }
}

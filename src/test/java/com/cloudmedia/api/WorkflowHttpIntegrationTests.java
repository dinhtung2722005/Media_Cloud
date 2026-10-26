package com.cloudmedia.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmedia.api.messaging.*;
import com.cloudmedia.api.security.AuthRateLimiter;
import com.cloudmedia.api.service.StorageMaintenance;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workflow-http;DB_CLOSE_DELAY=-1",
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
class WorkflowHttpIntegrationTests {
  static final Path root =
      Path.of("target", "http-workflow-tests", UUID.randomUUID().toString()).toAbsolutePath();

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry properties) {
    properties.add("app.storage.base-dir", () -> root.resolve("media").toString());
    properties.add("app.storage.temp-dir", () -> root.resolve("temp").toString());
  }

  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @MockBean AuthRateLimiter rateLimiter;
  @MockBean MediaJobPublisher publisher;
  @MockBean JobOutboxDispatcher dispatcher;
  @MockBean StorageMaintenance maintenance;

  @Test
  void realHttpAuthUploadResumeCompleteListingAndCancellation() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"   \",\"password\":\"\"}"))
        .andExpect(status().isBadRequest());
    String credentials = "{\"username\":\"flowtester\",\"password\":\"password123\"}";
    mvc.perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
        .andExpect(status().isOk());
    var cookie =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("access_token");
    assertThat(cookie).isNotNull();
    String upload = UUID.randomUUID().toString();
    String init =
        "{\"uploadId\":\"" + upload + "\",\"filename\":\"hello.txt\",\"size\":5,\"totalChunks\":1}";
    mvc.perform(
            post("/api/files/uploads")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(init))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receivedChunks").isEmpty());
    mvc.perform(
            multipart("/api/files/upload-chunk")
                .file(new MockMultipartFile("file", "hello".getBytes()))
                .param("fileGuid", upload)
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .cookie(cookie))
        .andExpect(status().isOk());
    mvc.perform(get("/api/files/uploads/" + upload).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receivedChunks[0]").value(0));
    String complete =
        "{\"fileGuid\":\"" + upload + "\",\"originalFilename\":\"hello.txt\",\"totalChunks\":1}";
    String first =
        mvc.perform(
                post("/api/files/merge-chunks")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(complete))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = mapper.readTree(first).get("id").asText();
    mvc.perform(
            post("/api/files/merge-chunks")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(complete))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id));
    mvc.perform(get("/api/folders").cookie(cookie).param("q", "hello").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[0].id").value(id))
        .andExpect(jsonPath("$.files[0].storagePath").doesNotExist())
        .andExpect(jsonPath("$.totalFiles").value(1));
    mvc.perform(get("/api/folders").cookie(cookie).param("size", "500"))
        .andExpect(status().isBadRequest());
    String submitted =
        mvc.perform(
                post("/api/files/youtube")
                    .cookie(cookie)
                    .header("Idempotency-Key", "http-job")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://youtu.be/example\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String job = mapper.readTree(submitted).get("jobId").asText();
    mvc.perform(get("/api/media/jobs").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].jobId").value(job));
    mvc.perform(delete("/api/media/jobs/" + job).cookie(cookie))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }
}

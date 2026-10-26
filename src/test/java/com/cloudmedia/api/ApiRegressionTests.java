package com.cloudmedia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmedia.api.cache.DriveCache;
import com.cloudmedia.api.controller.AuthController;
import com.cloudmedia.api.controller.ChunkUploadController;
import com.cloudmedia.api.controller.FolderController;
import com.cloudmedia.api.controller.MediaController;
import com.cloudmedia.api.controller.MediaJobController;
import com.cloudmedia.api.controller.YouTubeController;
import com.cloudmedia.api.entity.AuthSession;
import com.cloudmedia.api.entity.MediaFile;
import com.cloudmedia.api.entity.MediaJob;
import com.cloudmedia.api.entity.User;
import com.cloudmedia.api.messaging.JobSubmissionException;
import com.cloudmedia.api.repository.*;
import com.cloudmedia.api.security.*;
import com.cloudmedia.api.service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {
      AuthController.class,
      FolderController.class,
      ChunkUploadController.class,
      MediaController.class,
      YouTubeController.class,
      MediaJobController.class
    },
    properties = {
      "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "app.cache.enabled=false"
    })
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtUtils.class,
  AuthSessionService.class,
  DriveListingService.class,
  DriveCache.class
})
class ApiRegressionTests {
  @Autowired MockMvc mvc;
  @Autowired JwtUtils jwt;
  @MockBean UserService userService;
  @MockBean UserRepository users;
  @Autowired AuthSessionService sessionService;
  @MockBean AuthSessionRepository sessions;
  @MockBean FolderService folders;
  @MockBean FolderRepository folderRepository;
  @MockBean MediaFileRepository files;
  @MockBean MediaService mediaService;
  @MockBean FileService fileService;
  @MockBean MediaEngineService engine;
  @MockBean MediaJobService jobs;
  @MockBean StringRedisTemplate redis;
  @MockBean AuthRateLimiter rateLimiter;

  private User user;
  private String access;
  private String refresh;
  private final Map<String, AuthSession> storedSessions = new HashMap<>();
  private final Set<String> revokedSessions = new HashSet<>();

  @BeforeEach
  void setUp() {
    when(folderRepository.findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(org.springframework.data.domain.Page.empty());
    when(files.findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(org.springframework.data.domain.Page.empty());
    user = new User();
    user.setId("a5f72037-0395-4c82-82f5-b814cd19e89c");
    user.setUsername("alice");
    when(sessions.saveAndFlush(any(AuthSession.class)))
        .thenAnswer(
            invocation -> {
              AuthSession session = invocation.getArgument(0);
              storedSessions.put(session.getId(), session);
              return session;
            });
    when(sessions.existsByIdAndUserIdAndRevokedFalseAndExpiresAtAfter(
            anyString(), anyString(), any(Date.class)))
        .thenAnswer(
            invocation -> {
              AuthSession session = storedSessions.get(invocation.getArgument(0));
              return session != null
                  && session.getUserId().equals(invocation.getArgument(1))
                  && session.getExpiresAt().after(invocation.getArgument(2))
                  && !revokedSessions.contains(session.getId());
            });
    when(sessions.revoke(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              revokedSessions.add(invocation.getArgument(0));
              return 1;
            });
    Map<String, String> tokens = sessionService.issueTokens(user);
    access = tokens.get("accessToken");
    refresh = tokens.get("refreshToken");
  }

  @Test
  void loginCookiesAuthenticateRootListingWithFiles() throws Exception {
    when(userService.loginUser("alice", "password"))
        .thenReturn(Map.of("accessToken", access, "refreshToken", refresh));
    var login =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"password\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    Cookie cookie = login.getCookie("access_token");
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(login.getHeaders(HttpHeaders.SET_COOKIE))
        .anyMatch(
            value ->
                value.startsWith("refresh_token=" + refresh) && value.contains("Path=/api/auth;"));
    when(folders.getFolders(user.getId(), null)).thenReturn(List.of());
    MediaFile rootFile = new MediaFile();
    rootFile.setId("root-file");
    when(files.findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(rootFile)));

    mvc.perform(get("/api/folders").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.folders").isArray())
        .andExpect(jsonPath("$.files[0].id").value("root-file"));
  }

  @Test
  void expiredAccessDoesNotBlockValidRefresh() throws Exception {
    String expired =
        Jwts.builder()
            .setSubject(user.getId())
            .claim("token_type", "access")
            .setExpiration(new Date(0))
            .signWith(
                Keys.hmacShaKeyFor(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)))
            .compact();
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    mvc.perform(
            post("/api/auth/refresh")
                .cookie(new Cookie("access_token", expired), new Cookie("refresh_token", refresh)))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("access_token"));
  }

  @Test
  void tokenTypesCannotBeExchanged() throws Exception {
    mvc.perform(get("/api/folders").header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", access)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesBothTokensAndClearsTheirCookiePaths() throws Exception {
    var response =
        mvc.perform(
                post("/api/auth/logout")
                    .cookie(
                        new Cookie("access_token", access), new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(revokedSessions).containsExactly(jwt.getSessionId(access));
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .anyMatch(
            value ->
                value.startsWith("access_token=;")
                    && value.contains("Path=/;")
                    && value.contains("Max-Age=0"))
        .anyMatch(
            value ->
                value.startsWith("refresh_token=;")
                    && value.contains("Path=/api/auth;")
                    && value.contains("Max-Age=0"));
    mvc.perform(get("/api/folders").cookie(new Cookie("access_token", access)))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutAfterRefreshRejectsEveryAccessTokenFromThatSession() throws Exception {
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    String otherSessionAccess = sessionService.issueTokens(user).get("accessToken");
    var renewed =
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("access_token");
    assertThat(renewed.getValue()).isNotEqualTo(access);
    mvc.perform(post("/api/auth/logout").cookie(renewed, new Cookie("refresh_token", refresh)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/folders").cookie(new Cookie("access_token", access)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/folders").cookie(renewed)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/folders").cookie(new Cookie("access_token", otherSessionAccess)))
        .andExpect(status().isOk());
  }

  @Test
  void bearerLogoutAlsoRevokesTheSession() throws Exception {
    mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk());
    mvc.perform(get("/api/folders").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownSessionAndMismatchedOwnerAreRejected() throws Exception {
    String unknown = jwt.generateAccessToken(user, UUID.randomUUID().toString());
    mvc.perform(get("/api/folders").cookie(new Cookie("access_token", unknown)))
        .andExpect(status().isUnauthorized());
    User other = new User();
    other.setId(UUID.randomUUID().toString());
    other.setUsername("bob");
    String wrongOwner = jwt.generateAccessToken(other, jwt.getSessionId(access));
    mvc.perform(get("/api/folders").cookie(new Cookie("access_token", wrongOwner)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void conversionRejectsClientPathsAndFilesOwnedByAnotherUser() throws Exception {
    mvc.perform(
            post("/api/media/convert-to-mp3")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fileId\":\"file\",\"inputPath\":\"outside.mp4\",\"outputPath\":\"outside.mp3\"}"))
        .andExpect(status().isBadRequest());
    when(fileService.requireOwnedFile("foreign", user.getId()))
        .thenThrow(com.cloudmedia.api.domain.DomainException.missing());
    mvc.perform(
            post("/api/media/convert-to-mp3")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileId\":\"foreign\"}"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(jobs);
  }

  @Test
  void conversionAndYouTubeReturnRabbitJobIds() throws Exception {
    when(jobs.enqueueConversion(user.getId(), "source")).thenReturn("convert-job");
    mvc.perform(
            post("/api/media/convert-to-mp3")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileId\":\"source\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value("convert-job"));
    when(jobs.enqueueYouTube(user.getId(), "https://youtu.be/example", null))
        .thenReturn("youtube-job");
    mvc.perform(
            post("/api/files/youtube")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://youtu.be/example\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value("youtube-job"));
  }

  @Test
  void brokerFailureReturns503ForBothSubmissionApis() throws Exception {
    var failure =
        new JobSubmissionException("failed-job", new IllegalStateException("Broker unavailable"));
    when(jobs.enqueueConversion(user.getId(), "source")).thenThrow(failure);
    mvc.perform(
            post("/api/media/convert-to-mp3")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileId\":\"source\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.jobId").value("failed-job"));
    when(jobs.enqueueYouTube(user.getId(), "https://youtu.be/example", null)).thenThrow(failure);
    mvc.perform(
            post("/api/files/youtube")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://youtu.be/example\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void jobStatusAndCancellationUseAuthenticatedOwner() throws Exception {
    var job =
        new MediaJob(
            UUID.randomUUID().toString(),
            user.getId(),
            MediaJob.Type.YOUTUBE,
            null,
            "https://youtu.be/example",
            null);
    when(jobs.requireOwned(job.getId(), user.getId())).thenReturn(job);
    mvc.perform(get("/api/media/jobs/" + job.getId()).cookie(new Cookie("access_token", access)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"));
    mvc.perform(delete("/api/media/jobs/" + job.getId()).cookie(new Cookie("access_token", access)))
        .andExpect(status().isAccepted());
    verify(jobs).cancel(job.getId(), user.getId());
  }

  @Test
  void anonymousApiAndWebSocketRequestsReturn401() throws Exception {
    mvc.perform(get("/api/folders")).andExpect(status().isUnauthorized());
    mvc.perform(get("/ws/info")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/folders").cookie(new Cookie("jwt_token", access)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedChunkUploadUsesServerIdentity() throws Exception {
    mvc.perform(
            multipart("/api/files/upload-chunk")
                .file(new MockMultipartFile("file", new byte[] {1, 2, 3}))
                .param("fileGuid", "3c0cdc6a-5370-4614-982f-8365b160fc14")
                .param("chunkIndex", "0")
                .param("totalChunks", "1")
                .param("userId", "other-user")
                .cookie(new Cookie("access_token", access)))
        .andExpect(status().isOk());
    verify(mediaService)
        .saveChunk(
            any(), eq("3c0cdc6a-5370-4614-982f-8365b160fc14"), eq(0), eq(1), eq(user.getId()));
  }

  @Test
  void mergeEndpointReturnsCreatedFileAndRejectsInvalidUploads() throws Exception {
    MediaFile file = new MediaFile();
    file.setId("assembled-file");
    when(mediaService.mergeChunks("upload", "video.mp4", 2, user.getId(), null)).thenReturn(file);
    mvc.perform(
            post("/api/files/merge-chunks")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fileGuid\":\"upload\",\"originalFilename\":\"video.mp4\",\"totalChunks\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("assembled-file"));
    when(mediaService.mergeChunks("upload", "video.mp4", 2, user.getId(), null))
        .thenThrow(new IllegalArgumentException("Missing chunk"));
    mvc.perform(
            post("/api/files/merge-chunks")
                .cookie(new Cookie("access_token", access))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fileGuid\":\"upload\",\"originalFilename\":\"video.mp4\",\"totalChunks\":2}"))
        .andExpect(status().isBadRequest());
  }
}

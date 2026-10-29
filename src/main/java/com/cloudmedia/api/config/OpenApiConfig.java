package com.cloudmedia.api.config;

import com.cloudmedia.api.controller.AuthController;
import com.cloudmedia.api.controller.PublicShareController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {
  @Bean
  public OpenAPI mediaDriveOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Media Cloud API")
                .version("v1")
                .description(
                    "Quản lý tệp, thư mục, upload, tác vụ media và liên kết chia sẻ. Trong Swagger"
                        + " UI, chạy POST /api/auth/login với tài khoản của bạn trước; trình duyệt"
                        + " tự lưu và gửi cookie HttpOnly cho các lần Try it out tiếp theo. Login"
                        + " không trả JWT trong JSON. API client có access token có thể dùng"
                        + " Authorize → bearerAuth. Cookie access_token được ưu tiên nếu có cả hai."
                        + " Các thao tác Try it out thực sự thay đổi dữ liệu của tài khoản đang"
                        + " đăng nhập."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Access token của một phiên còn hiệu lực; chỉ nhập token, không thêm"
                                + " Bearer."))
                .addSecuritySchemes(
                    "cookieAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("access_token")
                        .description(
                            "Cookie HttpOnly được cấp bởi POST /api/auth/login. Đăng nhập qua Try"
                                + " it out; không nhập cookie thủ công vào Authorize.")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .addSecurityItem(new SecurityRequirement().addList("cookieAuth"));
  }

  @Bean
  public OperationCustomizer mediaDriveOperations() {
    return (operation, handler) -> {
      Class<?> controller = handler.getBeanType();
      if (controller == AuthController.class || controller == PublicShareController.class) {
        operation.setSecurity(List.of());
      }
      String tag =
          switch (controller.getSimpleName()) {
            case "AuthController" -> "Xác thực";
            case "FileController" -> "Tệp";
            case "ChunkUploadController" -> "Upload từng phần";
            case "FolderController" -> "Thư mục";
            case "FileShareController" -> "Quản lý liên kết chia sẻ";
            case "PublicShareController" -> "Truy cập liên kết chia sẻ";
            case "MediaController", "YouTubeController" -> "Xử lý media";
            case "MediaJobController" -> "Tác vụ nền";
            default -> controller.getSimpleName();
          };
      operation.setTags(List.of(tag));
      return operation;
    };
  }
}

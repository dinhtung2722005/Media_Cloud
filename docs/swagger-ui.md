# Swagger UI / OpenAPI

Khởi động ứng dụng như hướng dẫn trong README, rồi truy cập:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html` (hoặc `/swagger-ui.html`).
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`.

Thay host/cổng nếu ứng dụng chạy ở địa chỉ khác. Tài liệu được sinh từ các controller `/api/**`, gồm xác thực, thư mục, tệp, upload từng phần, xử lý media, tác vụ nền và liên kết chia sẻ. Hai endpoint upload có bộ chọn file dạng multipart.

## Thử API bằng tài khoản của bạn

1. Mở nhóm **Xác thực**, chọn `POST /api/auth/login` → **Try it out**.
2. Nhập JSON với `username` và `password` của bạn, rồi chọn **Execute**. Nếu chưa có tài khoản, dùng `/api/auth/register` trước.
3. Sau khi login trả 200, thử API được bảo vệ, ví dụ `GET /api/folders`. Trình duyệt tự lưu/gửi cookie HttpOnly trên cùng origin; không cần chép token vào Swagger.
4. Khi access token hết hạn, chạy `POST /api/auth/refresh`; khi kết thúc, chạy `POST /api/auth/logout`.

Login chỉ trả thông báo trong JSON, JWT được cấp trong cookie. Không nhập cookie vào ô Authorize vì JavaScript không thể tự đặt header Cookie trong trình duyệt. API client đã có access token hợp lệ có thể chọn **Authorize → bearerAuth** và dán token (không thêm tiền tố `Bearer`). Cookie `access_token` được ưu tiên nếu có cả cookie và header; đăng xuất phiên cookie trước khi thử token của một phiên khác.

Swagger và web chính dùng chung phiên trên cùng origin. Các lệnh tạo, xóa, chia sẻ và xử lý media thực thi trực tiếp trên dữ liệu của tài khoản đang đăng nhập. API truy cập liên kết chia sẻ không cần đăng nhập nhưng vẫn cần token chia sẻ còn hiệu lực.

## Bật/tắt

`SWAGGER_ENABLED` mặc định `true`. Đặt `SWAGGER_ENABLED=false` rồi khởi động lại để tắt cả giao diện và OpenAPI JSON/YAML; các đường dẫn này cũng không còn được cho phép truy cập ẩn danh. Ví dụ PowerShell:

```powershell
$env:SWAGGER_ENABLED = 'false'
./mvnw.cmd spring-boot:run
```

Khi bật, chỉ các yêu cầu GET/HEAD tới trang tài liệu, tài nguyên `/swagger-ui/**` và `/v3/api-docs*` đã liệt kê được truy cập ẩn danh. Quyền truy cập API nghiệp vụ không thay đổi. Token/cookie cũ bị thu hồi không ngăn người dùng mở lại trang tài liệu để đăng nhập.

Không lưu Bearer token qua lần tải lại trang (`persistAuthorization=false`), không dùng Swagger Petstore mặc định và không gửi OpenAPI tới trình validator bên ngoài. Các API dùng HttpOnly cookie được thử trực tiếp trên origin của ứng dụng.

## Phiên bản

Dùng `springdoc-openapi-starter-webmvc-ui:2.6.0` theo [bảng tương thích chính thức](https://springdoc.org/v2/faq.html#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot-) cho Spring Boot 3.3.x hiện tại. Khi nâng Spring Boot, chọn lại dòng Springdoc tương ứng.

## Kiểm chứng

- Toàn bộ backend: 103 bài, 95 thành công, 8 bài tích hợp Redis/RabbitMQ không bật nên bỏ qua; không có lỗi. Trong đó 4 bài mới kiểm tra UI/tài nguyên, OpenAPI/multipart, xác thực và tắt Swagger bằng biến môi trường.
- JavaScript: 20/20 bài thành công.
- Chrome headless với database H2 và storage tách biệt: hiển thị 30 thao tác, đăng nhập qua nút Execute, gọi danh sách thư mục bằng cookie, chọn file/upload trả 201, logout rồi gọi lại API trả 401. Không có lỗi JavaScript hoặc yêu cầu ra origin bên ngoài.

Kiểm thử này không thay thế kiểm thử triển khai với SQL Server và reverse proxy thực tế. Các kiểm thử Maven dùng profile `isolated-tests` được mô tả trong README.

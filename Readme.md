Tính năng nổi bật (Core Features)
Upload Video: Frontend chia file thành các mảnh tối đa 5 MB, gửi tuần tự đến `/api/files/upload-chunk`, sau đó gọi `/api/files/merge-chunks`. Mảnh tạm được lưu riêng theo người dùng và mã upload. Server kiểm tra đủ mảnh và quyền sở hữu thư mục trước khi gộp; chỉ xóa mảnh tạm sau khi lưu file và metadata thành công.

Xử Lý Ngầm Bất Đồng Bộ (Background Processing): RabbitMQ nhận tác vụ tải YouTube/chuyển MP4 sang MP3 qua Spring AMQP. Hàng đợi và message được lưu bền; API chờ publisher confirm trước khi trả HTTP 202. SQL Server lưu chủ sở hữu, đầu vào và trạng thái tác vụ trong bảng `media_jobs`.

Streaming: Spring MVC trả toàn bộ file với HTTP 200 khi không có Range, trả phần dữ liệu được yêu cầu với HTTP 206 và trả HTTP 416 khi Range vượt kích thước file. File rỗng cũng được phục vụ bình thường.

Giao Tiếp Thời Gian Thực (Real-time): Spring WebSocket gửi tiến độ tải YouTube đến kênh cá nhân `/user/queue/progress`. Kết nối yêu cầu xác thực, chỉ nhận đăng ký kênh cá nhân và không cho client phát thông báo tiến độ. Tiến độ upload file được frontend cập nhật sau mỗi mảnh.

Bảo Mật (Security): JWT kết hợp Spring Security và BCrypt. Access token (15 phút) và refresh token (7 ngày) cùng trỏ đến một bản ghi trong bảng `auth_sessions`. Mỗi request kiểm tra phiên còn hoạt động; đăng xuất vô hiệu hóa toàn bộ token của phiên, kể cả token trước khi refresh, đồng thời đóng WebSocket của phiên đó. Giao diện đưa tên file/thư mục/tác vụ vào DOM bằng `textContent`, không ghép chúng vào HTML hoặc inline event handler.

Công nghệ sử dụng (Tech Stack)
Ngôn ngữ: Java (JDK 17+)

Framework: Spring Boot, Spring Data JPA, Spring Security 

Cơ sở dữ liệu: SQL Server (Quản lý siêu dữ liệu - Metadata)

Background Jobs: RabbitMQ và Spring AMQP, có retry và dead-letter queue

Real-time Communication: Spring WebSocket

Xác thực & Bảo mật: JWT, BCrypt

Kiến trúc Hệ thống (System Architecture)
Hệ thống được thiết kế theo mô hình Client-Server kết hợp xử lý bất đồng bộ:

RESTful API: Cung cấp các endpoint chuẩn hóa để Frontend giao tiếp (CRUD metadata, thao tác file).

Worker / Queue: API lưu thông tin tác vụ rồi gửi JSON `{ "version": 1, "jobId": "..." }` tới RabbitMQ. Consumer đọc đầu vào từ database, chạy FFmpeg/yt-dlp và xác nhận message sau khi xử lý. Mặc định có hai consumer, mỗi consumer lấy một message. Worker chạy trong cùng tiến trình với API; `MEDIA_WORKER_ENABLED=false` tắt consumer. Tiến độ vẫn gửi qua WebSocket tới người dùng sở hữu tác vụ.

Database Layer: Sử dụng mô hình danh sách kề (Adjacency List) trong SQL Server để tổ chức cấu trúc "Thư mục ảo" cho từng người dùng riêng biệt.

Chạy dự án

1. Chuẩn bị JDK 17, SQL Server với database `CloudMediaDrive`, FFmpeg, yt-dlp và RabbitMQ (hoặc Docker Desktop để chạy broker bằng Compose).
2. Bắt buộc đặt `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` và `APP_JWT_SECRET`. Không còn mật khẩu hoặc khóa JWT mặc định trong source. `SPRING_DATASOURCE_URL` có thể đặt để đổi địa chỉ/database. Khóa JWT phải là Base64 của ít nhất 32 byte ngẫu nhiên; trên PowerShell có thể tạo và đặt cho phiên shell hiện tại bằng `$env:APP_JWT_SECRET = & ./scripts/New-JwtSecret.ps1`.
3. Đặt `RABBITMQ_USERNAME` và `RABBITMQ_PASSWORD` cho cả broker lẫn ứng dụng. Nếu dùng Docker, chạy `docker compose -f compose.rabbitmq.yml up -d --wait` trong cùng shell. Compose lưu dữ liệu RabbitMQ vào volume và chỉ mở cổng trên localhost. Tài khoản/vhost trong Compose chỉ được tạo khi volume khởi tạo lần đầu; khi đổi thông tin trên volume đã có dữ liệu, cập nhật tài khoản trong broker tương ứng.
4. Thiết lập các biến môi trường bên dưới nếu cần, rồi chạy `./mvnw.cmd spring-boot:run` trên Windows hoặc `./mvnw spring-boot:run` trên Linux/macOS.
5. Mở `http://localhost:8080`. RabbitMQ Management ở `http://localhost:15672`, đăng nhập bằng tài khoản broker đã đặt.

Ví dụ đặt thông tin RabbitMQ trên PowerShell (thay mật khẩu trước khi chạy):

```powershell
$env:RABBITMQ_USERNAME = 'media_cloud'
$env:RABBITMQ_PASSWORD = '<mat-khau-rieng-cua-ban>'
docker compose -f compose.rabbitmq.yml up -d --wait
./mvnw.cmd spring-boot:run
```

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `RABBITMQ_USERNAME` | Bắt buộc | Tài khoản RabbitMQ |
| `RABBITMQ_PASSWORD` | Bắt buộc | Mật khẩu RabbitMQ |
| `RABBITMQ_HOST` | `localhost` | Địa chỉ broker |
| `RABBITMQ_PORT` | `5672` | Cổng AMQP |
| `RABBITMQ_VHOST` | `/` | Virtual host, tài khoản cần quyền cấu hình/đọc/ghi trong vhost |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | Cổng Management khi dùng Compose |
| `RABBITMQ_CONFIRM_TIMEOUT_MS` | `5000` | Thời gian chờ broker xác nhận message |
| `MEDIA_WORKER_ENABLED` | `true` | Bật consumer trong ứng dụng |
| `MEDIA_WORKER_CONCURRENCY` | `2` | Số tác vụ chạy đồng thời trong mỗi ứng dụng |
| `MEDIA_JOB_MAX_ATTEMPTS` | `2` | Tổng số lần thử mỗi delivery, bao gồm lần đầu (1–10) |
| `APP_STORAGE_BASE_DIR` | `./storage/media` | Thư mục lưu file hoàn chỉnh |
| `APP_STORAGE_TEMP_DIR` | `./storage/temp` | Thư mục lưu mảnh tạm |
| `FFMPEG_PATH` | `ffmpeg` | Tên lệnh trong PATH hoặc đường dẫn tới executable FFmpeg |
| `YT_DLP_PATH` | `yt-dlp` | Tên lệnh trong PATH hoặc đường dẫn tới executable yt-dlp |
| `SECURE_COOKIES` | `false` | Đặt `true` khi chạy bằng HTTPS |
| `UPLOAD_MAX_ACTIVE_PER_USER` | `5` | Số upload tạm đang mở tối đa cho mỗi người dùng |
| `UPLOAD_MAX_TEMP_BYTES_PER_USER` | `1073741824` | Tổng dung lượng mảnh tạm tối đa mỗi người dùng (1 GiB) |
| `UPLOAD_EXPIRE_AFTER_MS` | `86400000` | Xóa upload không hoạt động trong 24 giờ |
| `UPLOAD_CLEANUP_INTERVAL_MS` | `3600000` | Chu kỳ dọn upload tạm (1 giờ) |
| `MEDIA_JOB_TIMEOUT_SECONDS` | `1800` | Giới hạn thời gian mỗi lần chạy FFmpeg/yt-dlp (30 phút) |
| `YOUTUBE_MAX_BYTES` | `1073741824` | Giới hạn dung lượng file YouTube đầu ra |

Thư mục lưu trữ được tạo khi cần. Với đường dẫn Windows chứa dấu cách, đặt nguyên đường dẫn trong giá trị biến môi trường. API upload một lần `/api/files/upload` vẫn hỗ trợ file tối đa 500 MB; frontend dùng chunked upload, tối đa 10000 mảnh cho mỗi file và chịu giới hạn dung lượng tạm ở trên. Hạn mức tạm tính tổng mọi upload của cùng tài khoản, không tính lại mảnh được gửi thay thế. Upload hết hạn được dọn theo lịch và trước khi tiếp nhận mảnh mới.

Chuyển MP3: gửi `POST /api/media/convert-to-mp3` với JSON `{ "fileId": "ID-file-của-bạn" }`. API từ chối `inputPath`/`outputPath`. Worker kiểm tra lại chủ sở hữu và đường dẫn thực nằm trong storage, tạo tên đầu ra UUID và lưu file MP3 mới vào cùng thư mục ảo. Các job chuyển đổi cũ chứa đường dẫn client phải được gửi lại bằng API mới.

Xóa file: nếu xóa dữ liệu trên ổ đĩa thất bại, API trả lỗi và giữ metadata để thử lại. Nếu dữ liệu đã xóa nhưng database tạm lỗi, gọi lại thao tác xóa sẽ hoàn tất việc dọn metadata. Khi upload không lưu được metadata, file mới được dọn bỏ.

Hai API tạo tác vụ `POST /api/media/convert-to-mp3` và `POST /api/files/youtube` trả HTTP 202 kèm `jobId`. Dùng `GET /api/media/jobs/{jobId}` để xem trạng thái và `DELETE /api/media/jobs/{jobId}` để hủy; chỉ chủ sở hữu được truy cập. Các trạng thái gồm `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `PUBLISH_FAILED`; khi hoàn tất có thêm `resultFileId`. Trạng thái `FAILED` có thể xuất hiện giữa các lần retry. Nếu broker không xác nhận hoặc không định tuyến được message, API trả HTTP 503 kèm `jobId`; kiểm tra trạng thái trước khi gửi lại vì xác nhận có thể bị mất sau khi broker đã nhận message.

Worker kiểm tra yêu cầu hủy trong lúc chạy công cụ, kết thúc tiến trình cùng các tiến trình con và dọn đầu ra chưa hoàn tất; timeout cũng dọn theo cơ chế này. Hủy tác vụ đã hoàn tất trả HTTP 409. Khi worker bị ngắt, message được trả lại RabbitMQ. RabbitMQ có thể giao lại message; worker bỏ qua job đã hoàn tất/hủy và dùng ID đầu ra cố định để nhận ra kết quả đã lưu trước đó.

Topology RabbitMQ (hai hàng đợi dùng kiểu quorum, cấu hình được kiểm thử với RabbitMQ 4.3):

| Thành phần | Tên |
| --- | --- |
| Direct exchange | `media.jobs.exchange` |
| Routing key | `media.process` |
| Hàng đợi xử lý | `media.jobs` |
| Dead-letter exchange | `media.jobs.dlx` |
| Dead-letter routing key | `media.failed` |
| Hàng đợi lỗi | `media.jobs.failed` |

Mặc định tác vụ lỗi được thử lại một lần sau 1 giây, sau đó chuyển message gốc vào `media.jobs.failed`. Message sai phiên bản hoặc thiếu dữ liệu tác vụ vào hàng đợi lỗi ngay. Có thể kiểm tra và gửi lại message từ RabbitMQ Management sau khi khắc phục nguyên nhân. Không có vòng retry vô hạn cho lỗi xử lý. `x-consumer-timeout` của hàng đợi được tính đủ cho tổng thời gian các lần thử; nếu đổi timeout/số lần thử khi hàng đợi đã tồn tại, cập nhật cấu hình hàng đợi trong lúc bảo trì sau khi đã xử lý hết message.

`GET /api/folders` trả về `{ "folders": [...], "files": [...] }` cho thư mục gốc. `GET /api/folders/{folderId}` trả thông tin thư mục, thư mục con và file thuộc người dùng hiện tại.

Kiểm thử: `./mvnw.cmd test` (Windows) hoặc `./mvnw test`. Các test hồi quy dùng repository giả và thư mục tạm, không cần SQL Server, RabbitMQ hay chạy FFmpeg/yt-dlp thật.

Kiểm thử với broker thật trên Windows: `./scripts/Test-RabbitMq.ps1`. Script cần Docker đang chạy, tự tạo container/vhost/tài khoản tạm, kiểm tra publish confirm, gửi/nhận JSON, acknowledgment, retry và dead-letter, rồi xóa container. Hai test tích hợp được bỏ qua trong lần chạy test thông thường. Có thể truyền `-MavenRepository 'duong-dan-cache-maven'` và `-Jdk26Compatibility` nếu cần.

Kiểm thử luồng JavaScript bằng Node.js: `node --test src/test/js/app.test.cjs`.

Nếu chạy test bằng JDK 26 như môi trường hiện tại, Mockito trong bộ dependency này cần thêm cờ tương thích: `./mvnw.cmd "-DargLine=-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading" clean test`. JDK mục tiêu của dự án vẫn là 17.

Khi chuyển từ JobRunr, xử lý hết tác vụ cũ trước khi triển khai hoặc gửi lại chúng qua API mới; bản cập nhật không tự chuyển job trong bảng JobRunr sang RabbitMQ. Dependency, cấu hình và dashboard JobRunr đã được gỡ. Không cần xóa các bảng JobRunr để chạy bản mới.

Khi cập nhật, đăng nhập lại vì token cũ chưa có mã phiên `sid`. Hibernate hiện được cấu hình `ddl-auto=update` để tạo thêm bảng `auth_sessions` và `media_jobs`; database account cần quyền cập nhật schema. Dùng khóa JWT mới và thay mật khẩu database cũ nếu đã dùng giá trị từng nằm trong source. Thay đổi source không xóa bí mật khỏi lịch sử Git. Ứng dụng đọc biến môi trường của tiến trình, không tự nạp file `.env` (Docker Compose có cơ chế nạp riêng).

Tính năng chia sẻ liên kết trên giao diện hiện vẫn đang phát triển.

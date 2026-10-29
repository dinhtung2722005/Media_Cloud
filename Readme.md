Tính năng nổi bật (Core Features)
Upload Video: Frontend chia file thành các mảnh tối đa 5 MB, gửi tuần tự đến `/api/files/upload-chunk`, sau đó gọi `/api/files/merge-chunks`. Mảnh tạm được lưu riêng theo người dùng và mã upload. Server kiểm tra đủ mảnh và quyền sở hữu thư mục trước khi gộp; chỉ xóa mảnh tạm sau khi lưu file và metadata thành công.

Xử lý nền: API lưu job và outbox trong cùng transaction SQL Server, rồi trả HTTP 202. Dispatcher gửi RabbitMQ với publisher confirm và tự gửi lại khi mất kết nối/xác nhận. Worker nhận lease trong database; mỗi lượt chạy có đường dẫn kết quả riêng.

Streaming: Spring MVC trả toàn bộ file với HTTP 200 khi không có Range, trả phần dữ liệu được yêu cầu với HTTP 206 và trả HTTP 416 khi Range vượt kích thước file. File rỗng cũng được phục vụ bình thường.

Giao Tiếp Thời Gian Thực (Real-time): Spring WebSocket gửi tiến độ tải YouTube đến kênh cá nhân `/user/queue/progress`. Kết nối yêu cầu xác thực, chỉ nhận đăng ký kênh cá nhân và không cho client phát thông báo tiến độ. Tiến độ upload file được frontend cập nhật sau mỗi mảnh.

Bảo Mật (Security): JWT kết hợp Spring Security và BCrypt. Access token (15 phút) và refresh token (7 ngày) cùng trỏ đến một bản ghi trong bảng `auth_sessions`. Mỗi request kiểm tra phiên còn hoạt động; đăng xuất vô hiệu hóa toàn bộ token của phiên, kể cả token trước khi refresh, đồng thời đóng WebSocket của phiên đó. Giao diện đưa tên file/thư mục/tác vụ vào DOM bằng `textContent`, không ghép chúng vào HTML hoặc inline event handler.

Công nghệ sử dụng (Tech Stack)
Ngôn ngữ: Java (JDK 17+)

Framework: Spring Boot, Spring Data JPA, Spring Security 

Cơ sở dữ liệu: SQL Server (Quản lý siêu dữ liệu - Metadata)

Caching: Redis qua Spring Data Redis, cache-aside cho kết quả duyệt thư mục

Background Jobs: RabbitMQ và Spring AMQP, có retry và dead-letter queue

Real-time Communication: Spring WebSocket

Giao diện: dashboard tiếng Việt với thanh điều hướng thu gọn trên điện thoại, thống kê theo loại tệp trong thư mục đang mở, tìm kiếm hỗ trợ tên không dấu, sắp xếp theo tên/dung lượng và chuyển giữa lưới/danh sách. Hộp thoại hỗ trợ tạo/đổi tên/xóa thư mục hoặc tệp, nhập YouTube và xem ảnh/phát video/âm thanh. Menu video có thao tác chuyển MP3; tác vụ RabbitMQ có nút hủy trong bảng tiến trình. Có thể kéo thả một tệp vào vùng tải lên; luồng chia mảnh và làm mới phiên được giữ nguyên.

Xác thực & Bảo mật: JWT, BCrypt

Kiến trúc Hệ thống (System Architecture)
Hệ thống được thiết kế theo mô hình Client-Server kết hợp xử lý bất đồng bộ:

RESTful API: Cung cấp các endpoint chuẩn hóa để Frontend giao tiếp (CRUD metadata, thao tác file).

Worker / Queue: outbox gửi JSON `{ "version": 1, "jobId": "..." }` tới RabbitMQ. Consumer đọc đầu vào từ database, chạy FFmpeg/yt-dlp và chốt metadata cùng trạng thái COMPLETED trong một transaction. Mặc định có hai consumer, mỗi consumer lấy một message. `MEDIA_WORKER_ENABLED=false` tắt consumer. Redis pub/sub chuyển thông báo tiến độ/thu hồi phiên tới các node; client polling SQL qua API để khôi phục sự kiện bị mất.

Database Layer: Sử dụng mô hình danh sách kề (Adjacency List) trong SQL Server để tổ chức cấu trúc "Thư mục ảo" cho từng người dùng riêng biệt.

Redis cache: các API duyệt thư mục dùng cache JSON theo tài khoản, thư mục và bộ tham số phân trang/tìm kiếm/sắp xếp với TTL mặc định 60 giây. Quyền sở hữu thư mục vẫn được kiểm tra trực tiếp trong database trước mỗi lần đọc, kể cả cache hit. Nội dung file, phiên đăng nhập và trạng thái job không được cache. Mỗi trang mặc định 50 tệp và 50 thư mục, tối đa 100 mỗi loại; response DTO không chứa đường dẫn storage.

Tạo/đổi tên/xóa thư mục hoặc tệp, hoàn tất upload/gộp mảnh và tác vụ tải YouTube/chuyển MP3 đều đổi phiên bản cache của người dùng sau khi ghi database thành công; transaction ngoài cùng phải commit trước khi vô hiệu hóa cache. Lua chỉ cho phép ghi cache nếu phiên bản chưa thay đổi trong lúc đọc database, tránh một truy vấn chậm ghi lại dữ liệu cũ sau cập nhật. Phiên bản ngẫu nhiên cũng tránh dùng lại cache cũ khi Redis evict key. Các key cũ tự hết hạn, không cần quét/xóa cache của tài khoản khác.

Khi Redis lỗi, API đọc từ SQL Server; một lần vô hiệu hóa cache thất bại sẽ tạm bỏ qua cache trong instance đó tới khi TTL cũ kết thúc. Các instance khác có thể còn đọc cache cũ tối đa TTL nếu thông báo cập nhật bị mất trong lúc Redis gián đoạn. Thay đổi trực tiếp trong database được phản ánh khi cache hết hạn. Mỗi entry cache tối đa 2 MiB.

Tài liệu API tương tác: sau khi khởi động, mở `http://localhost:8080/swagger-ui/index.html`. Chạy `/api/auth/login` trong Swagger để thử API bằng cookie hiện có. OpenAPI JSON tại `/v3/api-docs`; đặt `SWAGGER_ENABLED=false` để tắt. Xem [hướng dẫn Swagger UI](docs/swagger-ui.md).

Chạy dự án

1. Chuẩn bị JDK 17, SQL Server với database `CloudMediaDrive`, FFmpeg, yt-dlp, RabbitMQ và Redis (hoặc Docker Desktop để chạy broker/cache bằng Compose).
2. Bắt buộc đặt `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` và `APP_JWT_SECRET`. Không còn mật khẩu hoặc khóa JWT mặc định trong source. `SPRING_DATASOURCE_URL` có thể đặt để đổi địa chỉ/database. Khóa JWT phải là Base64 của ít nhất 32 byte ngẫu nhiên; trên PowerShell có thể tạo và đặt cho phiên shell hiện tại bằng `$env:APP_JWT_SECRET = & ./scripts/New-JwtSecret.ps1`.
3. Đặt `RABBITMQ_USERNAME` và `RABBITMQ_PASSWORD` cho cả broker lẫn ứng dụng. Nếu dùng Docker, chạy `docker compose -f compose.rabbitmq.yml up -d --wait` trong cùng shell. Compose lưu dữ liệu RabbitMQ vào volume và chỉ mở cổng trên localhost. Tài khoản/vhost trong Compose chỉ được tạo khi volume khởi tạo lần đầu; khi đổi thông tin trên volume đã có dữ liệu, cập nhật tài khoản trong broker tương ứng.
4. Thiết lập các biến môi trường bên dưới nếu cần, rồi chạy `./mvnw.cmd spring-boot:run` trên Windows hoặc `./mvnw spring-boot:run` trên Linux/macOS.
5. Mở `http://localhost:8080`. RabbitMQ Management ở `http://localhost:15672`, đăng nhập bằng tài khoản broker đã đặt.

Redis: đặt `REDIS_PASSWORD` cho broker cache và ứng dụng, rồi chạy `docker compose -f compose.rabbitmq.yml -f compose.redis.yml up -d --wait redis`. Compose chỉ mở Redis trên `127.0.0.1:6379`, dùng mật khẩu, giới hạn cache 128 MiB với `allkeys-lru` và không lưu cache xuống ổ đĩa. Có thể khởi động cả hai dịch vụ bằng cùng lệnh sau khi bỏ đối số `redis`. Nếu lưu biến ở phạm vi User trên Windows, mở lại terminal/VS Code trước khi chạy ứng dụng. Dùng `REDIS_CACHE_ENABLED=false` để tắt cache khi không cần Redis.

Ví dụ đặt thông tin RabbitMQ trên PowerShell (thay mật khẩu trước khi chạy):

```powershell
$env:RABBITMQ_USERNAME = 'media_cloud'
$env:RABBITMQ_PASSWORD = '<mat-khau-rieng-cua-ban>'
docker compose -f compose.rabbitmq.yml up -d --wait
./mvnw.cmd spring-boot:run
```

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `REDIS_HOST` | `localhost` | Địa chỉ Redis |
| `REDIS_PORT` | `6379` | Cổng Redis |
| `REDIS_USERNAME` | `default` | Tài khoản Redis ACL |
| `REDIS_PASSWORD` | Rỗng | Mật khẩu Redis; bắt buộc với Compose đi kèm |
| `REDIS_DATABASE` | `0` | Database Redis, nên tách riêng giữa các môi trường |
| `REDIS_CACHE_ENABLED` | `true` | Bật cache danh sách thư mục/tệp |
| `REDIS_CACHE_TTL_SECONDS` | `60` | TTL cache từ 1 đến 86400 giây |
| `REDIS_CONNECT_TIMEOUT_MS` | `500` | Timeout kết nối Redis |
| `REDIS_TIMEOUT_MS` | `300` | Timeout mỗi lệnh Redis |
| `RABBITMQ_USERNAME` | Bắt buộc | Tài khoản RabbitMQ |
| `RABBITMQ_PASSWORD` | Bắt buộc | Mật khẩu RabbitMQ |
| `RABBITMQ_HOST` | `localhost` | Địa chỉ broker |
| `RABBITMQ_PORT` | `5672` | Cổng AMQP |
| `RABBITMQ_VHOST` | `/` | Virtual host, tài khoản cần quyền cấu hình/đọc/ghi trong vhost |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | Cổng Management khi dùng Compose |
| `RABBITMQ_CONFIRM_TIMEOUT_MS` | `5000` | Thời gian chờ broker xác nhận message |
| `MEDIA_WORKER_ENABLED` | `true` | Bật consumer trong ứng dụng |
| `MEDIA_WORKER_CONCURRENCY` | `2` | Số tác vụ chạy đồng thời trong mỗi ứng dụng |
| `MEDIA_JOB_MAX_ATTEMPTS` | `2` | Tổng lượt xử lý media mỗi job; cũng giới hạn retry transport khi SQL/consumer lỗi (1–10) |
| `APP_STORAGE_BASE_DIR` | `./storage/media` | Thư mục lưu file hoàn chỉnh |
| `APP_STORAGE_TEMP_DIR` | `./storage/temp` | Thư mục lưu mảnh tạm |
| `FFMPEG_PATH` | `ffmpeg` | Tên lệnh trong PATH hoặc đường dẫn tới executable FFmpeg |
| `YT_DLP_PATH` | `yt-dlp` | Tên lệnh trong PATH hoặc đường dẫn tới executable yt-dlp |
| `SECURE_COOKIES` | `false` | Đặt `true` khi chạy bằng HTTPS |
| `UPLOAD_MAX_ACTIVE_PER_USER` | `5` | Số upload tạm đang mở tối đa cho mỗi người dùng |
| `UPLOAD_MAX_TEMP_BYTES_PER_USER` | `1073741824` | Tổng dung lượng mảnh tạm tối đa mỗi người dùng (1 GiB) |
| `UPLOAD_EXPIRE_AFTER_MS` | `86400000` | Hết hạn upload không hoạt động sau 24 giờ; lượt merge được giữ ít nhất tới hết lease |
| `UPLOAD_CLEANUP_INTERVAL_MS` | `3600000` | Chu kỳ dọn upload tạm (1 giờ) |
| `MEDIA_JOB_TIMEOUT_SECONDS` | `1800` | Giới hạn thời gian mỗi lần chạy FFmpeg/yt-dlp (30 phút) |
| `YOUTUBE_MAX_BYTES` | `1073741824` | Giới hạn dung lượng file YouTube đầu ra |

Thư mục lưu trữ được tạo khi cần. Với đường dẫn Windows chứa dấu cách, đặt nguyên đường dẫn trong biến môi trường. API upload một lần `/api/files/upload` hỗ trợ tối đa 500 MB; frontend dùng chunk 5 MiB. UploadSession lưu kích thước, reservation và kết quả trong SQL. Gửi lại complete trả cùng tệp; chọn lại cùng tệp sau mất mạng sẽ tiếp tục các chunk còn thiếu. Upload hết hạn được dọn định kỳ; API từ chối ghi tiếp vào session hết hạn.

Chuyển MP3: gửi `POST /api/media/convert-to-mp3` với JSON `{ "fileId": "ID-file-của-bạn" }`. API từ chối `inputPath`/`outputPath`. Worker kiểm tra lại chủ sở hữu và đường dẫn thực nằm trong storage, tạo tên đầu ra UUID và lưu file MP3 mới vào cùng thư mục ảo. Các job chuyển đổi cũ chứa đường dẫn client phải được gửi lại bằng API mới.

Xóa file: ghi trạng thái `Deleting` trước khi xóa dữ liệu. Nếu ổ đĩa chưa xóa được, API trả 202; scheduler tiếp tục dọn, tệp được ẩn khỏi thư viện và không thể tải xuống. Metadata chỉ bị xóa sau khi dữ liệu đã được dọn. Tệp nguồn và thư mục đang có job/upload hoạt động trả 409 khi yêu cầu xóa.

Hai API tạo tác vụ trả HTTP 202 kèm `jobId` ngay sau khi commit SQL. Header `Idempotency-Key` giúp gửi lại cùng yêu cầu trả cùng job; dùng lại khóa với đầu vào khác trả 409. Các trạng thái hoạt động gồm `PENDING_DISPATCH`, `QUEUED`, `RUNNING`, `RETRYING`, `CANCEL_REQUESTED`; trạng thái cuối gồm `COMPLETED`, `FAILED`, `CANCELLED`. `PUBLISH_FAILED` chỉ còn được đọc để phục hồi dữ liệu cũ. `GET /api/media/jobs?active=true` khôi phục danh sách; GET/DELETE theo jobId xem chi tiết/hủy. Chỉ chủ sở hữu được truy cập.

Worker heartbeat lease trong SQL và kiểm tra hủy khi chạy công cụ. Transaction finalization quyết định giữa hủy và hoàn tất; hủy thắng không lưu metadata, hoàn tất thắng khiến API hủy trả 409. Worker mất lease không được công bố kết quả; cleanup chỉ tác động đường dẫn riêng của lượt chạy. Scheduler phục hồi lease hết hạn, outbox chưa gửi và job chờ delivery quá lâu.

Topology RabbitMQ (hai hàng đợi dùng kiểu quorum, cấu hình được kiểm thử với RabbitMQ 4.3):

| Thành phần | Tên |
| --- | --- |
| Direct exchange | `media.jobs.exchange` |
| Routing key | `media.process` |
| Hàng đợi xử lý | `media.jobs` |
| Dead-letter exchange | `media.jobs.dlx` |
| Dead-letter routing key | `media.failed` |
| Hàng đợi lỗi | `media.jobs.failed` |

Lỗi công cụ được ghi `RETRYING` và đưa lại vào outbox với backoff, tối đa hai lượt mặc định; hết lượt chuyển `FAILED` và trả reservation. Lỗi transport/SQL được retry tại consumer rồi vào `media.jobs.failed`; lease recovery/outbox vẫn đối soát trạng thái SQL. Message sai phiên bản bị từ chối ngay. Không replay job đã terminal để tạo lại kết quả; gửi yêu cầu mới với khóa mới khi muốn chạy lại. Nếu đổi timeout/số lần thử, cập nhật queue arguments trong lúc bảo trì sau khi xử lý hết message.

`GET /api/folders` và `GET /api/folders/{folderId}` nhận `page`, `size`, `q`, `sort` (`name`, `name-desc`, `size-asc`, `size-desc`), `type` (`all`, `video`, `image`, `audio`, `document`). Response gồm danh sách DTO, `hasMore`, tổng số kết quả và thống kê toàn thư mục.

Kiểm thử: `./mvnw.cmd test` (Windows) hoặc `./mvnw test`. Suite dùng cả mock và database H2 thật cho transaction, FK, quota và tranh chấp nhiều luồng. Công cụ media được giả lập; test H2 không thay thế kiểm thử SQL Server thật.

Kiểm thử Redis thật: sau khi Redis đang chạy và shell đã nhận các biến `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, đặt `$env:RUN_REDIS_IT = 'true'` và chạy `./mvnw.cmd '-Dtest=RedisCacheIntegrationTests' test`. Sáu test kiểm tra cache hit, JSON/Unicode, TTL, tách tài khoản/thư mục, vô hiệu hóa giữa các instance và tránh ghi lại cache cũ. Test dùng database 15 và chỉ dọn key theo prefix ngẫu nhiên của riêng test; không dùng `FLUSHDB`/`FLUSHALL`. Khi không có `RUN_REDIS_IT`, chúng được bỏ qua.

Kiểm thử với broker thật trên Windows: `./scripts/Test-RabbitMq.ps1`. Script cần Docker đang chạy, tự tạo container/vhost/tài khoản tạm, kiểm tra publish confirm, gửi/nhận JSON, acknowledgment, retry và dead-letter, rồi xóa container. Hai test tích hợp được bỏ qua trong lần chạy test thông thường. Có thể truyền `-MavenRepository 'duong-dan-cache-maven'` và `-Jdk26Compatibility` nếu cần.

Kiểm thử luồng JavaScript bằng Node.js: `node --test src/test/js/app.test.cjs`.

Nếu chạy test bằng JDK 26 như môi trường hiện tại, Mockito trong bộ dependency này cần thêm cờ tương thích: `./mvnw.cmd "-DargLine=-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading" clean test`. JDK mục tiêu của dự án vẫn là 17.

Khi chuyển từ JobRunr, xử lý hết tác vụ cũ trước khi triển khai hoặc gửi lại chúng qua API mới; bản cập nhật không tự chuyển job trong bảng JobRunr sang RabbitMQ. Dependency, cấu hình và dashboard JobRunr đã được gỡ. Không cần xóa các bảng JobRunr để chạy bản mới.

Triển khai bản workflow mới: xem [hướng dẫn vận hành và nâng cấp schema](docs/workflow-upgrade.md). Migration SQL Server phải chạy trên bản sao dữ liệu trước, rồi triển khai với `HIBERNATE_DDL_AUTO=validate`. Các node cần mount chung cả media/temp storage và dùng chung SQL, RabbitMQ, Redis.

Chia sẻ tệp: trong menu tệp chọn **Chia sẻ liên kết** để tạo URL có thời hạn (mặc định 24 giờ), sao chép hoặc thu hồi. Người nhận xem/phát/tải tệp mà không cần đăng nhập; video vẫn tua được bằng HTTP Range. Xem [chi tiết tính năng và migration 002](docs/file-sharing.md).

Nếu IDE tự biên dịch vào `target/classes` trong lúc Maven chạy, dùng `mvn -Pisolated-tests test` để tách output kiểm thử vào `target/isolated-tests`. Profile này không đổi cấu hình build mặc định. Kiểm thử frontend: `node --test src/test/js/*.test.cjs`.

[Báo cáo rà soát tổng thể](docs/project-review.md) phân biệt lỗi bảo mật đã tái hiện, rủi ro phụ thuộc cấu hình và các mục nâng cấp. Các phát hiện chưa được sửa chỉ bằng việc bổ sung link sharing.

Khi cập nhật, đăng nhập lại vì token cũ chưa có mã phiên `sid`. Hibernate hiện được cấu hình `ddl-auto=update` để tạo thêm bảng `auth_sessions` và `media_jobs`; database account cần quyền cập nhật schema. Dùng khóa JWT mới và thay mật khẩu database cũ nếu đã dùng giá trị từng nằm trong source. Thay đổi source không xóa bí mật khỏi lịch sử Git. Ứng dụng đọc biến môi trường của tiến trình, không tự nạp file `.env` (Docker Compose có cơ chế nạp riêng).

Tính năng chia sẻ liên kết chưa được hỗ trợ.

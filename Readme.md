# Media Cloud

Ứng dụng quản lý và chia sẻ tệp cá nhân, hỗ trợ upload tiếp tục sau gián đoạn, xem video bằng HTTP Range, tải YouTube và chuyển MP3 qua tác vụ nền.

## Tính năng

- **Quản lý thư viện:** tạo, đổi tên, xóa tệp/thư mục; tìm kiếm tiếng Việt không dấu, lọc loại tệp, sắp xếp, phân trang và chuyển giữa lưới/danh sách. Giao diện tiếng Việt hỗ trợ điện thoại.
- **Upload:** giao diện chia tệp thành các mảnh tối đa 5 MiB, tiếp tục các mảnh còn thiếu khi chọn lại cùng tệp. Server lưu upload session, kiểm tra kích thước/quyền sở hữu và trả cùng tệp khi gửi lặp yêu cầu hoàn tất. API upload một lần hỗ trợ tối đa 500 MB.
- **Xem và streaming:** xem ảnh, phát video/âm thanh; HTTP Range trả 206 để tua video, 416 khi khoảng byte vượt kích thước. Nội dung không thuộc nhóm preview an toàn được tải về dạng attachment.
- **Xử lý media:** tải YouTube bằng yt-dlp, chuyển tệp thành MP3 bằng FFmpeg. API trả 202 và `jobId`; giao diện theo dõi tiến độ và hỗ trợ hủy tác vụ.
- **Chia sẻ liên kết:** tạo link cho từng tệp, sao chép, chọn thời hạn và thu hồi. Người nhận xem/phát/tải tệp mà không cần đăng nhập; video chia sẻ vẫn hỗ trợ tua.
- **Xác thực:** JWT trong cookie HttpOnly hoặc Bearer header; access token 15 phút, refresh token 7 ngày. Đăng xuất thu hồi phiên và đóng WebSocket tương ứng. Mật khẩu được băm bằng BCrypt.
- **Redis:** cache kết quả duyệt thư mục theo tài khoản và tham số truy vấn; pub/sub chuyển thông báo tiến độ và thu hồi phiên giữa các node.
- **Swagger UI:** xem tài liệu và thử API, gồm upload multipart, đăng nhập bằng cookie và Bearer token.

## Công nghệ

| Thành phần | Công nghệ |
| --- | --- |
| Backend | Java 17, Spring Boot 3.3.0, Spring MVC, Spring Data JPA |
| Xác thực | Spring Security, JWT, BCrypt |
| Metadata | SQL Server |
| Hàng đợi | RabbitMQ, Spring AMQP, transactional outbox |
| Cache và thông báo giữa node | Redis, Spring Data Redis |
| Tiến độ trực tiếp | Spring WebSocket/STOMP và API polling |
| Công cụ media | FFmpeg, yt-dlp |
| Giao diện | HTML/CSS, JavaScript, Bootstrap |
| Tài liệu API | Springdoc OpenAPI 2.6.0, Swagger UI |

## Chạy trên máy cá nhân

### 1. Chuẩn bị

- JDK 17; Maven Wrapper đã có trong repository.
- SQL Server với database `CloudMediaDrive`, tài khoản có quyền phù hợp.
- FFmpeg và yt-dlp trong `PATH`, hoặc cấu hình `FFMPEG_PATH` / `YT_DLP_PATH`.
- RabbitMQ và Redis; có thể chạy bằng Docker Compose đi kèm.
- Node.js nếu muốn chạy kiểm thử JavaScript.

### 2. Đặt biến môi trường

Ví dụ PowerShell, thay các giá trị trong dấu `<...>` bằng thông tin của bạn:

```powershell
$env:SPRING_DATASOURCE_USERNAME = '<tai-khoan-sql>'
$env:SPRING_DATASOURCE_PASSWORD = '<mat-khau-sql>'
$env:APP_JWT_SECRET = & ./scripts/New-JwtSecret.ps1
$env:RABBITMQ_USERNAME = 'media_cloud'
$env:RABBITMQ_PASSWORD = '<mat-khau-rabbitmq>'
$env:REDIS_PASSWORD = '<mat-khau-redis>'
```

Địa chỉ SQL mặc định là `localhost:1433`, database `CloudMediaDrive`. Đặt `SPRING_DATASOURCE_URL` để đổi kết nối:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:sqlserver://localhost:1433;databaseName=CloudMediaDrive;encrypt=true;trustServerCertificate=true'
```

Khóa JWT phải là Base64 của ít nhất 32 byte ngẫu nhiên. Lưu khóa đã tạo vào nơi quản lý cấu hình riêng và dùng lại giữa các lần khởi động; thay khóa sẽ làm token đã cấp không còn hợp lệ.

Ứng dụng Java đọc biến môi trường của tiến trình, **không tự nạp `.env`**. Docker Compose có cơ chế đọc `.env` riêng. Nếu lưu biến ở phạm vi User trên Windows, mở lại terminal/IDE để tiến trình nhận giá trị mới. Không commit mật khẩu, khóa JWT hoặc `.env`.

### 3. Chạy RabbitMQ và Redis

Bật Docker Desktop ở chế độ Linux containers, rồi chạy tại thư mục dự án trong cùng terminal đã đặt biến:

```powershell
docker compose -f compose.rabbitmq.yml -f compose.redis.yml up -d --wait
```

RabbitMQ và Redis chỉ mở cổng trên localhost. RabbitMQ lưu dữ liệu vào volume; Redis dùng mật khẩu, giới hạn cache 128 MiB với `allkeys-lru` và không lưu cache xuống đĩa.

Tài khoản/vhost RabbitMQ chỉ được khởi tạo khi volume mới được tạo lần đầu. Nếu đổi mật khẩu trong cấu hình khi volume đã có dữ liệu, cần cập nhật tài khoản broker tương ứng.

### 4. Khởi động ứng dụng

Windows:

```powershell
./mvnw.cmd spring-boot:run
```

Linux/macOS, sau khi đặt các biến môi trường tương ứng:

```bash
sh ./mvnw spring-boot:run
```

| Địa chỉ mặc định | Chức năng |
| --- | --- |
| http://localhost:8080 | Giao diện quản lý tệp |
| http://localhost:8080/swagger-ui/index.html | Swagger UI |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/v3/api-docs.yaml | OpenAPI YAML |
| http://localhost:15672 | RabbitMQ Management; dùng tài khoản broker |

## Chia sẻ và thu hồi liên kết

1. Trong menu của tệp, chọn **Chia sẻ liên kết**.
2. Chọn thời hạn **1 giờ, 24 giờ, 7 ngày hoặc 30 ngày**, rồi chọn **Tạo liên kết**.
3. Sao chép URL gửi cho người nhận. Người có URL có thể xem/phát/tải tệp trong thời gian link còn hiệu lực.
4. Muốn ngừng chia sẻ, mở lại hộp thoại và chọn **Thu hồi** cho liên kết tương ứng.

Mặc định link có hiệu lực 24 giờ. API cho phép 1–720 giờ theo cấu hình; tối đa 10 link đang hoạt động mỗi tệp và 1.000 mỗi tài khoản. URL đầy đủ chỉ được trả lúc tạo; nếu chưa sao chép, tạo link mới và thu hồi link cũ nếu cần.

Database chỉ lưu SHA-256 của token ngẫu nhiên 256 bit. Mỗi yêu cầu đọc kiểm tra lại thời hạn, trạng thái thu hồi và trạng thái tệp trong database; Redis không cache quyết định cho phép truy cập. API dữ liệu trả 404 khi link sai, hết hạn, đã thu hồi hoặc tệp không còn phục vụ. Trang HTML vẫn có thể mở để hiển thị thông báo không khả dụng.

Thu hồi chặn yêu cầu mới; dữ liệu người nhận đã tải xuống hoặc đã nằm trong buffer không thể thu hồi. Chia sẻ hiện áp dụng cho **từng tệp**; chia sẻ cả thư mục, mật khẩu cho link và thống kê lượt xem chưa được triển khai.

Xem [chi tiết tính năng chia sẻ và migration 002](docs/file-sharing.md).

## Sử dụng Swagger UI

Mở Swagger UI, chọn `POST /api/auth/login` → **Try it out**, nhập tài khoản rồi **Execute**. Trình duyệt tự lưu và gửi cookie HttpOnly khi thử các API được bảo vệ tiếp theo. Login không trả JWT trong JSON.

API client đã có access token hợp lệ có thể dùng **Authorize → bearerAuth**, chỉ nhập token. Cookie `access_token` được ưu tiên nếu có cả cookie và header. Swagger và giao diện chính dùng chung phiên trên cùng origin; các thao tác tạo/xóa/chia sẻ thực sự tác động đến dữ liệu của tài khoản đang đăng nhập.

Đặt `SWAGGER_ENABLED=false` rồi khởi động lại để tắt cả Swagger UI và OpenAPI JSON/YAML. Xem [hướng dẫn Swagger UI](docs/swagger-ui.md).

## API chính

Các API quản lý yêu cầu đăng nhập và kiểm tra chủ sở hữu. API public yêu cầu token chia sẻ còn hiệu lực.

| Nhóm | Endpoint |
| --- | --- |
| Tài khoản | `POST /api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` |
| Thư mục | `GET/POST /api/folders`, `GET/PUT/DELETE /api/folders/{id}` |
| Upload một lần | `POST /api/files/upload` với multipart |
| Upload từng phần | `POST /api/files/uploads`, `GET/DELETE /api/files/uploads/{id}`, `POST /api/files/upload-chunk`, `POST /api/files/merge-chunks` |
| Tệp | `PUT/DELETE /api/files/{id}`, `GET /api/files/download/{id}`, `GET /api/files/stream/{id}` |
| Tải YouTube | `POST /api/files/youtube` với `{ "url": "https://...", "folderId": null }` |
| Chuyển MP3 | `POST /api/media/convert-to-mp3` với `{ "fileId": "..." }` |
| Tác vụ nền | `GET /api/media/jobs`, `GET/DELETE /api/media/jobs/{id}` |
| Quản lý link | `POST/GET /api/files/{fileId}/shares`, `DELETE /api/files/{fileId}/shares/{id}` |
| Nhận tệp chia sẻ | `GET /s/{token}`, `GET /api/public/shares/{token}`, `GET/HEAD /api/public/shares/{token}/stream`, `GET/HEAD /api/public/shares/{token}/download` |

API duyệt thư mục nhận `page`, `size`, `q`, `sort` (`name`, `name-desc`, `size-asc`, `size-desc`) và `type` (`all`, `video`, `image`, `audio`, `document`). Mặc định mỗi trang có tối đa 50 tệp và 50 thư mục, cấu hình `size` tối đa 100; response có `hasMore`, tổng số kết quả và thống kê của thư mục.

Hai API tạo tác vụ media trả 202 sau khi commit SQL. Header `Idempotency-Key` giúp gửi lại cùng yêu cầu trả cùng job; dùng lại khóa với đầu vào khác trả 409. Truyền `fileId` khi chuyển MP3; API không nhận đường dẫn input/output từ client.

## Luồng xử lý và lưu trữ

**Tác vụ nền:** job và outbox được ghi trong cùng transaction SQL. Dispatcher gửi JSON `{ "version": 1, "jobId": "..." }` tới RabbitMQ và chờ publisher confirm. Consumer đọc đầu vào từ database, nhận lease, chạy FFmpeg/yt-dlp rồi chốt metadata cùng trạng thái `COMPLETED` trong một transaction.

Các trạng thái hoạt động gồm `PENDING_DISPATCH`, `QUEUED`, `RUNNING`, `RETRYING`, `CANCEL_REQUESTED`; trạng thái cuối gồm `COMPLETED`, `FAILED`, `CANCELLED`. `PUBLISH_FAILED` chỉ còn phục vụ dữ liệu cũ. Worker heartbeat lease và kiểm tra hủy; scheduler phục hồi job có lease hết hạn hoặc outbox chưa gửi. Mỗi lượt chạy có đường dẫn đầu ra riêng để worker cũ không ghi đè kết quả mới.

**Tiến độ:** WebSocket chỉ cho đăng ký kênh cá nhân `/user/queue/progress`, không cho client tự phát tiến độ. Redis pub/sub chuyển sự kiện giữa các node; frontend polling API để khôi phục tiến độ bị mất. Tiến độ upload được cập nhật sau mỗi mảnh.

**Upload và xóa:** upload session lưu reservation và file kết quả trong SQL; hoàn tất lặp lại trả cùng tệp. File cần xóa được đánh dấu `Deleting` trước khi dọn dữ liệu; nếu ổ đĩa chưa xóa được, API trả 202 và scheduler tiếp tục xử lý. Metadata chỉ xóa sau khi dữ liệu được dọn. Tệp nguồn/thư mục đang có job hoặc upload hoạt động có thể trả 409 khi yêu cầu xóa.

**Redis cache:** cache JSON theo tài khoản, thư mục và bộ tham số truy vấn, TTL mặc định 60 giây, mỗi entry tối đa 2 MiB. Quyền sở hữu thư mục luôn được kiểm tra trong SQL, kể cả cache hit. Ghi dữ liệu thành công làm đổi phiên bản cache sau commit; Lua ngăn truy vấn cũ ghi lại kết quả sau cập nhật. Không cache nội dung tệp, phiên đăng nhập hoặc trạng thái job.

Khi Redis lỗi, API đọc SQL; instance gặp lỗi invalidation tạm bỏ qua cache đến khi TTL cũ kết thúc. Trong triển khai nhiều node, node khác có thể còn đọc dữ liệu cũ tối đa TTL nếu mất thông báo cập nhật. Thay đổi trực tiếp trong SQL được phản ánh khi cache hết hạn.

| Thành phần RabbitMQ | Tên |
| --- | --- |
| Direct exchange | `media.jobs.exchange` |
| Routing key | `media.process` |
| Hàng đợi xử lý quorum | `media.jobs` |
| Dead-letter exchange | `media.jobs.dlx` |
| Dead-letter routing key | `media.failed` |
| Hàng đợi lỗi quorum | `media.jobs.failed` |

Không replay job đã kết thúc để tạo lại kết quả; tạo yêu cầu mới với khóa mới. Khi đổi queue arguments, xử lý hết message và thực hiện thay đổi trong thời gian bảo trì.

## Biến môi trường

Các giá trị dưới đây lấy từ cấu hình hiện tại. Các hạn mức dung lượng được cấu hình theo byte.

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | SQL Server local, database `CloudMediaDrive` | Chuỗi kết nối JDBC |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Bắt buộc | Tài khoản database |
| `APP_JWT_SECRET` | Bắt buộc | Khóa JWT Base64, ít nhất 32 byte |
| `HIBERNATE_DDL_AUTO` | `update` | Dev có thể cập nhật schema; dùng `validate` sau migration khi triển khai |
| `SECURE_COOKIES` | `false` | Đặt `true` khi dùng HTTPS |
| `SWAGGER_ENABLED` | `true` | Bật Swagger UI và OpenAPI |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | `localhost`, `5672` | Kết nối AMQP |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Bắt buộc | Tài khoản broker |
| `RABBITMQ_VHOST` | `/` | Virtual host; tài khoản cần quyền cấu hình/đọc/ghi |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | Cổng giao diện quản trị trong Compose |
| `RABBITMQ_CONFIRM_TIMEOUT_MS` | `5000` | Timeout publisher confirm |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Kết nối Redis |
| `REDIS_USERNAME`, `REDIS_PASSWORD` | `default`, rỗng | Compose đi kèm bắt buộc đặt mật khẩu |
| `REDIS_DATABASE` | `0` | Database Redis |
| `REDIS_CACHE_ENABLED` | `true` | Bật cache thư mục |
| `REDIS_CACHE_TTL_SECONDS` | `60` | TTL cache; nhận 1–86400 giây |
| `REDIS_CONNECT_TIMEOUT_MS`, `REDIS_TIMEOUT_MS` | `500`, `300` | Timeout kết nối/lệnh Redis |
| `REDIS_EVENTS_ENABLED` | `true` | Pub/sub tiến độ và thu hồi phiên giữa node |
| `APP_STORAGE_BASE_DIR`, `APP_STORAGE_TEMP_DIR` | `./storage/media`, `./storage/temp` | Dữ liệu tệp và upload tạm |
| `STORAGE_VERIFY_IDENTITY` | `true` | Kiểm tra định danh volume với database khi khởi động |
| `FFMPEG_PATH`, `YT_DLP_PATH` | `ffmpeg`, `yt-dlp` | Executable trong PATH hoặc đường dẫn đầy đủ |
| `MEDIA_WORKER_ENABLED` | `true` | Bật consumer |
| `MEDIA_WORKER_CONCURRENCY` | `2` | Số consumer mỗi instance |
| `MEDIA_JOB_MAX_ATTEMPTS` | `2` | Giới hạn lượt xử lý; cấu hình 1–10 |
| `MEDIA_JOB_TIMEOUT_SECONDS` | `1800` | Timeout mỗi lần chạy công cụ |
| `MEDIA_JOB_LEASE_SECONDS` | `45` | Lease của worker |
| `MEDIA_OUTPUT_MAX_BYTES`, `YOUTUBE_MAX_BYTES` | `1073741824` mỗi biến | Giới hạn đầu ra 1 GiB |
| `STORAGE_QUOTA_BYTES` | `21474836480` | Hạn mức 20 GiB mỗi người dùng, gồm dung lượng đã giữ chỗ |
| `ACTIVE_JOBS_PER_USER`, `RUNNING_JOBS_PER_USER` | `10`, `2` | Số job hoạt động/chạy đồng thời mỗi người dùng |
| `UPLOAD_MAX_ACTIVE_PER_USER` | `5` | Số upload session hoạt động mỗi người dùng |
| `UPLOAD_MAX_TEMP_BYTES_PER_USER` | `1073741824` | Hạn mức upload tạm mỗi người dùng |
| `UPLOAD_EXPIRE_AFTER_MS` | `86400000` | Upload không hoạt động hết hạn sau 24 giờ |
| `UPLOAD_CLEANUP_INTERVAL_MS` | `3600000` | Chu kỳ dọn upload tạm |
| `STORAGE_ORPHAN_GRACE_SECONDS` | `86400` | Thời gian chờ trước khi dọn dữ liệu mồ côi |
| `SHARE_DEFAULT_HOURS`, `SHARE_MAX_HOURS` | `24`, `720` | Thời hạn mặc định/tối đa của liên kết chia sẻ |

## Docker và triển khai

Repository hiện có [compose.rabbitmq.yml](compose.rabbitmq.yml) và [compose.redis.yml](compose.redis.yml) để chạy broker/cache. **Chưa có Dockerfile hoặc Compose cho ứng dụng Java/SQL Server.** Lệnh dưới đây chỉ quản lý hai dịch vụ đã có:

```powershell
docker compose -f compose.rabbitmq.yml -f compose.redis.yml up -d --wait
docker compose -f compose.rabbitmq.yml -f compose.redis.yml ps
docker compose -f compose.rabbitmq.yml -f compose.redis.yml logs --tail 100
```

Để đóng gói ứng dụng vào Docker, cần thực hiện thêm:

1. Tạo Dockerfile build JAR bằng Maven/JDK 17 và runtime Java 17 có FFmpeg/yt-dlp; chạy ứng dụng bằng user không có quyền root.
2. Tạo `.dockerignore` loại `.git`, `.env`, `target`, `storage` và các file riêng của IDE khỏi build context.
3. Thêm service ứng dụng vào Compose, truyền biến môi trường, mở cổng 8080, cấu hình healthcheck và phụ thuộc RabbitMQ/Redis sẵn sàng.
4. Dùng `rabbitmq:5672` và `redis:6379` khi các service ở cùng mạng Compose. Nếu SQL Server chạy trên Windows với Docker Desktop, dùng `host.docker.internal:1433`; nếu SQL Server là service cùng mạng, dùng tên service đó. `localhost` trong container chỉ chính container.
5. Mount media và temp storage bền vững. Khi dùng database cũ, chuyển dữ liệu tệp cùng metadata và giữ định danh volume tương ứng; volume mới rỗng không thay thế được storage cũ.
6. Build, khởi động rồi kiểm tra đăng nhập, upload, streaming/Range, job nền, Swagger, tạo và thu hồi link trên môi trường triển khai.

Khi triển khai nhiều node, mount chung cả media/temp storage và dùng chung SQL Server, RabbitMQ, Redis. Kiểm tra marker volume giúp phát hiện cấu hình sai nhưng không chứng minh các node thực sự dùng chung filesystem.

Với database hiện có, sao lưu và kiểm chứng migration trên bản sao trước khi chạy theo thứ tự:

1. [001-durable-workflows.sql](docs/migrations/001-durable-workflows.sql).
2. [002-file-sharing.sql](docs/migrations/002-file-sharing.sql).
3. Khởi động bản mới với `HIBERNATE_DDL_AUTO=validate`.

Database trống trong môi trường phát triển có thể dùng mặc định `update`; database phải được tạo trước. Các migration chưa được xác nhận chạy trên SQL Server thật trong workspace này. Xem [hướng dẫn nâng cấp workflow](docs/workflow-upgrade.md).

Khi công khai ứng dụng, cấu hình domain/HTTPS, đặt `SECURE_COOKIES=true`, dùng chứng chỉ SQL Server được tin cậy và quyết định bật/tắt Swagger. Che token chia sẻ trong access logs/analytics. Backup cả database và storage.

## Kiểm thử

Backend với JDK 17 trên Windows:

```powershell
./mvnw.cmd -Pisolated-tests test
```

Linux/macOS: `sh ./mvnw -Pisolated-tests test`. Profile `isolated-tests` ghi output vào `target/isolated-tests`, tránh xung đột khi IDE tự biên dịch vào `target/classes`.

Nếu dùng JDK 26, bộ dependency Mockito hiện tại cần thêm cờ tương thích:

```powershell
./mvnw.cmd -Pisolated-tests '-DargLine=-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading' test
```

Kiểm thử JavaScript:

```powershell
node --test src/test/js/*.test.cjs
```

Kiểm thử Redis thật: đặt thông tin kết nối Redis trong môi trường, bật `RUN_REDIS_IT` rồi chạy:

```powershell
$env:RUN_REDIS_IT = 'true'
./mvnw.cmd -Pisolated-tests '-Dtest=RedisCacheIntegrationTests' test
```

Sáu bài Redis dùng database 15 và chỉ dọn key có prefix riêng; không dùng `FLUSHDB`/`FLUSHALL`. Với JDK 26, thêm cờ `argLine` ở trên.

Kiểm thử RabbitMQ thật trên Windows:

```powershell
./scripts/Test-RabbitMq.ps1
```

Script tạo container/vhost/tài khoản tạm và dọn sau kiểm thử. Có thể truyền `-MavenRepository '<duong-dan-cache>'` và `-Jdk26Compatibility`. Script chạy `mvn clean`, vì vậy cần lưu lại kết quả trong `target` nếu muốn giữ chúng.

Lần kiểm chứng tính năng Swagger ghi nhận **103 bài backend: 95 thành công, 8 bỏ qua** do chưa bật kiểm thử tích hợp Redis/RabbitMQ; **20/20 bài JavaScript thành công**. Chrome đã kiểm tra đăng nhập, gọi API bằng cookie, upload qua Swagger, logout và API trả 401 sau logout. Luồng chia sẻ đã được thử tạo/thu hồi link, truy cập ẩn danh và phát/tua video. H2 không thay thế SQL Server thật và các kiểm thử tự động không bao phủ mọi môi trường mạng/triển khai.

## Tài liệu và giới hạn hiện tại

| Tài liệu | Nội dung |
| --- | --- |
| [Swagger UI](docs/swagger-ui.md) | Đăng nhập, thử API và cấu hình tài liệu |
| [Chia sẻ liên kết](docs/file-sharing.md) | API, thu hồi, giới hạn và vận hành |
| [Workflow và migration](docs/workflow-upgrade.md) | Transaction, lease, quota, recovery, triển khai nhiều node |
| [Rà soát nghiệp vụ](docs/business-flow-review.md) | Các phát hiện nghiệp vụ và bối cảnh cải thiện |
| [Streaming](docs/streaming-review.md) | Range, thời gian phản hồi và tải thumbnail |
| [Video dài và tua liên tục](docs/streaming-long-seek-review.md) | Đo hiệu suất video 20 phút ở các mức băng thông |
| [Rà soát tổng thể](docs/project-review.md) | Các phát hiện bảo mật, điều kiện tái hiện và hạng mục còn mở |

Streaming hiện phục vụ tệp qua HTTP Range, chưa có HLS hoặc tự điều chỉnh bitrate. Mạng chậm và tải nhiều thumbnail video có thể làm tăng thời gian chờ; xem báo cáo streaming để biết điều kiện đo.

Báo cáo tổng thể vẫn ghi nhận các vấn đề cần xử lý, gồm ranh giới đọc tệp của FFmpeg, dependency cũ và kiểm soát request/xác thực. Bổ sung link sharing và Swagger không đồng nghĩa các phát hiện này đã được sửa.

Khi nâng từ JobRunr, xử lý hết job cũ hoặc gửi lại qua API mới; không có cơ chế tự chuyển job cũ sang RabbitMQ. Token cũ thiếu `sid` cần đăng nhập lại. Nếu từng dùng mật khẩu/khóa JWT trong lịch sử source, cần thay chúng; xóa giá trị khỏi mã hiện tại không xóa bí mật khỏi lịch sử Git.

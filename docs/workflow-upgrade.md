# Nâng cấp luồng nghiệp vụ

Job/outbox và kết quả cuối được lưu trong transaction SQL. Khóa tài khoản chỉ giữ trong các bước cập nhật ngắn; tải video, chuyển đổi và ghép dữ liệu chạy ngoài transaction. Mỗi attempt dùng `objects/<lease-token>/content.ext`; client chỉ biết fileId.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| STORAGE_QUOTA_BYTES | 21474836480 | 20 GiB/tài khoản, tính cả file đang xóa và reservation |
| ACTIVE_JOBS_PER_USER | 10 | Tổng job đang hoạt động/tài khoản |
| RUNNING_JOBS_PER_USER | 2 | Job có lease đang chạy/tài khoản |
| MEDIA_OUTPUT_MAX_BYTES | 1073741824 | 1 GiB reservation và giới hạn kết quả/job |
| MEDIA_JOB_LEASE_SECONDS | 45 | Lease worker; heartbeat mỗi khoảng 15 giây |
| STORAGE_ORPHAN_GRACE_SECONDS | 86400 | Chỉ xét output mồ côi cũ hơn 24 giờ; tối thiểu 1 giờ |
| STORAGE_VERIFY_IDENTITY | true | Kiểm tra media/temp volume trùng với database khi khởi động |
| REDIS_EVENTS_ENABLED | true | Fan-out tiến độ và thu hồi phiên giữa các node |
| HIBERNATE_DDL_AUTO | update | Dùng `validate` cho môi trường đã quản lý migration |

Lỗi tạm khi publish không làm mất job đã nhận. Dispatcher thử lại mỗi 5 giây khi lỗi; job chờ quá 5 phút được đối soát delivery. Lỗi công cụ có số lượt hữu hạn, lưu `RETRYING` trước lượt sau; lỗi nghiệp vụ không được thử lại tự động. Phiên upload mở hết hạn sau 24 giờ không hoạt động; bản ghi terminal được giữ để complete lặp lại trả cùng kết quả hoặc 410 nếu tệp đã bị xóa.

Xóa file ghi `Deleting` trước khi unlink. Scheduler đối soát mỗi phút; metadata và dung lượng sử dụng chỉ được giải phóng khi xóa xong. Dọn orphan mỗi giờ chỉ xét thư mục UUID trong `objects`, bỏ qua dữ liệu đã có metadata và token còn lease. File cũ ngoài `objects` không bị quét xóa. Không đặt orphan grace ngắn hơn thời gian chạy/merge tối đa và thời gian gián đoạn storage dự kiến.

## Nâng cấp SQL Server hiện có

1. Sao lưu database, media và temp volume. Dừng toàn bộ API/worker phiên bản cũ; không chạy đồng thời hai phiên bản state machine.
2. Trên bản sao database, chạy [001-durable-workflows.sql](migrations/001-durable-workflows.sql) bằng `sqlcmd -b -d CloudMediaDrive -i ...` với phương thức xác thực của môi trường. Script cần các bảng `dbo.users`, `dbo.folders`, `dbo.media_files`, `dbo.media_jobs` theo naming strategy hiện tại. Nếu schema khác, đối chiếu tên trước khi chạy.
3. Script từ chối thư mục/file/job có parent không tồn tại hoặc khác chủ sở hữu. Kiểm tra và xử lý từng bản ghi bằng quy tắc nghiệp vụ; script không xóa dữ liệu thay người vận hành. Chỉ dấu `media_schema_migrations.version=1` được ghi khi transaction thành công; chạy lại bản đã áp dụng sẽ bỏ qua.
4. Script thêm Unicode, chỉ mục, FK và các bảng/field mới; chuyển job RUNNING cũ sang RETRYING sau khi worker cũ đã dừng. Job QUEUED/PUBLISH_FAILED thiếu outbox được scheduler khôi phục; job không còn dung lượng sẽ FAILED với mã quota.
5. Triển khai với `HIBERNATE_DDL_AUTO=validate`. Kiểm thử đăng nhập, upload/resume/complete, YouTube, MP3, hủy ở thời điểm hoàn tất, xóa đang bận, Range streaming và broker outage trên SQL Server trước khi mở traffic.

Đường dẫn tuyệt đối cũ chỉ phục vụ được khi vẫn thuộc storage root hiện tại. Khi chuyển máy/mount point, phải sao chép dữ liệu và chuyển `storage_path` cũ thành key tương đối sau khi đối chiếu file thật; không sửa hàng loạt bằng chuỗi thay thế chưa được kiểm tra. File `.media-cloud-volume` được tạo trong mỗi root và token đăng ký ở `storage_identities`; giữ marker khi chuyển volume. Token khác database sẽ chặn khởi động để tránh nhận traffic vào storage sai.

## Chạy nhiều instance

Các instance phải dùng chung SQL Server, RabbitMQ, Redis và mount **cùng nội dung** ở cả `APP_STORAGE_BASE_DIR` lẫn `APP_STORAGE_TEMP_DIR`. Marker phát hiện danh tính volume khác database; hai bản sao mang cùng marker vẫn không chứng minh được filesystem đang chia sẻ. Kiểm tra quyền đọc/ghi và ngữ nghĩa tạo file/rename/locking của shared filesystem trước khi triển khai. Sticky sessions cần được cấu hình khi SockJS dùng transport nhiều HTTP request.

Redis pub/sub chuyển progress và revoke tới broker WebSocket cục bộ. Kênh này không bền; giao diện truy vấn danh sách job và trạng thái theo ID mỗi 10 giây để bù sự kiện mất. Socket bị thu hồi/hết hạn được đóng bằng pub/sub và kiểm tra SQL định kỳ 30 giây. Khi Redis lỗi, rate limit chuyển về bộ đếm cục bộ theo node, cache đọc SQL, job vẫn dựa trên SQL/RabbitMQ. Nếu cần giới hạn đăng nhập chung khi Redis gián đoạn, đặt thêm giới hạn ở gateway; chỉ tin địa chỉ client do proxy đáng tin cậy cung cấp.

## Hợp đồng API

- `POST /api/files/uploads`: `{uploadId, filename, size, totalChunks, folderId?, sha256?}`. SHA-256 tùy chọn, 64 ký tự hex. Response có `receivedChunks`, trạng thái và kết quả nếu đã hoàn tất.
- `GET /api/files/uploads/{uploadId}` và `DELETE ...`: xem phần đã nhận/hủy session. Frontend lưu uploadId theo tài khoản, thư mục, tên, kích thước, lastModified; chọn lại đúng tệp để tiếp tục. Session hết hạn sẽ được tạo mới.
- `POST /api/files/merge-chunks`: hoàn tất theo uploadId, lặp lại trả cùng fileId; chỉ một merge lease có hiệu lực. Kích thước và SHA-256 (nếu khai báo) được kiểm tra trước commit.
- Tạo job nhận `Idempotency-Key` tối đa 100 ký tự; cùng key/nội dung trả cùng jobId, khác nội dung trả 409. API upload một lần cũng kiểm tra tên, kích thước, thư mục và SHA-256 khi dùng key.
- Duyệt thư mục nhận `page>=0`, `1<=size<=100`, `q<=255`, sort/type cho phép. Mỗi trang có tối đa size thư mục và size file; `hasMore` xét cả hai danh sách. Response chỉ chứa DTO public. Thống kê `counts`/`bytes` tính toàn thư mục, trước tìm kiếm/lọc.
- Lỗi trả `{code,error}`; 400 dữ liệu sai, 404 không có quyền/không tồn tại, 409 xung đột/đang bận, 410 upload hết hạn/kết quả đã xóa, 413 quota byte, 429 giới hạn số lượng/tần suất, 503 hạ tầng tạm lỗi.

## Giới hạn kiểm chứng

| Vấn đề rà soát | Thay đổi | Kiểm thử liên quan |
| --- | --- | --- |
| G01 | Transactional outbox, confirm/retry và request key | MediaJobServiceTests: atomicity, lost confirm; RabbitMqIntegrationTests |
| G02–G03 | Claim SQL, lease/heartbeat, finalization nguyên tử, staging theo token | MediaJobServiceTests: hai worker, cancel/finish, lease cũ, rollback, commit không rõ kết quả |
| G04 | Khóa mutation theo tài khoản, chặn folder/source đang bận, FK | FolderCacheInvalidationTests, FileLifecycleTests |
| G05 | UploadSession, complete idempotent, status/resume, size/SHA-256 | MediaServiceTests, WorkflowHttpIntegrationTests; app.test.cjs: resume |
| G06 | DTO, validation server, lỗi có mã, rate limit Redis/fallback | ValidationTests, ApiRegressionTests, WorkflowHttpIntegrationTests |
| G07 | Reservation và giới hạn dung lượng/job theo tài khoản | MediaJobServiceTests, MediaServiceTests |
| G08 | API danh sách, trạng thái retry riêng, khôi phục/polling UI | app.test.cjs: polling, retry, cancellation |
| G09 | Redis fan-out, đóng socket hết phiên, kiểm tra volume identity | ClusterEventsTests, AuthenticatedWebSocketSessionsTests; boot smoke qua WorkflowHttpIntegrationTests |
| G10 | DTO, phân trang/tìm kiếm/lọc/sort SQL, cache theo query, migration | DriveListingServiceTests, RedisCacheIntegrationTests; app.test.cjs: pagination |
| G11 | Deleting bền, dọn upload/orphan có điều kiện, lease recovery | StorageMaintenanceTests, FileLifecycleTests, MediaJobServiceTests |

Kiểm thử tự động dùng H2 thật cho transaction/FK/claim và mock cho FFmpeg/yt-dlp, có suite Redis/RabbitMQ tùy chọn để chạy broker thật. Chưa có SQL Server được cấu hình trong workspace này: migration, collation/locking SQL Server và luồng nhiều container trên shared volume cần được kiểm chứng trong môi trường triển khai. Cần theo dõi dung lượng ổ đĩa, tuổi outbox, job hết lease, job FAILED/DLQ và upload reservation để phát hiện sự cố vận hành.

Kết quả xác nhận ngày 26/10/2026: **91 test backend, 0 failure/error/skipped**, gồm 6 test Redis thật và 2 test RabbitMQ thật trong vhost tạm đã dọn; **15 test JavaScript đều qua**. Chrome headless với API fixture kiểm tra đăng nhập, tìm kiếm/lọc/sắp xếp, điều hướng, hộp thoại, tạo/hủy job, preview và kích thước 320/390/768/1024/1440 px, không ghi nhận lỗi JavaScript. Log nằm ở `target/test-logs/workflows-full.log`, `target/test-logs/workflows-js.log`; kết quả trình duyệt ở `target/ui-review/results.json` (không đưa vào Git).

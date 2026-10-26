# Chia sẻ tệp bằng liên kết

Trong menu của tệp, chọn **Chia sẻ liên kết**, chọn thời hạn rồi **Tạo liên kết**. Sao chép URL để gửi. Người nhận mở được trang xem/phát/tải tệp mà không cần tài khoản. Chủ sở hữu có thể thu hồi từng liên kết trong cùng hộp thoại.

Thời hạn mặc định 24 giờ, các lựa chọn giao diện gồm 1 giờ, 24 giờ, 7 ngày và 30 ngày. API cho phép thời hạn 1–720 giờ theo cấu hình. Tối đa 10 liên kết đang hoạt động cho mỗi tệp và 1.000 cho mỗi tài khoản. Các link đang hoạt động được ưu tiên trong danh sách quản lý; lịch sử hiển thị tối đa 50 mục.

## Bảo vệ quyền truy cập

- Token ngẫu nhiên 256 bit; database chỉ lưu SHA-256. URL đầy đủ chỉ được trả khi tạo; nếu đã đóng hộp thoại mà chưa sao chép, tạo liên kết mới và có thể thu hồi liên kết cũ.
- Chỉ chủ sở hữu được tạo, xem danh sách hoặc thu hồi. Public endpoint chỉ hỗ trợ GET/HEAD và kiểm tra token, thời hạn, trạng thái thu hồi cùng quyền sở hữu/trạng thái Ready của file trên mỗi request. Không dùng cache Redis cho quyết định này.
- URL không chứa fileId hoặc đường dẫn storage. Metadata public chỉ gồm tên, phần mở rộng, kích thước và thời hạn. Token là quyền xem/tải tệp: người có URL sẽ có quyền đó trong thời gian hiệu lực.
- Trang public không dùng CDN/script bên thứ ba; CSP chỉ cho tài nguyên cùng origin, chặn nhúng frame, có `Referrer-Policy: no-referrer`, `no-store` và `noindex`. HTML/SVG/tệp không thuộc nhóm preview an toàn được tải về dạng attachment.
- Stream giữ hỗ trợ HTTP Range để tua video. Thu hồi/hết hạn chặn request mới; dữ liệu đã tải hoặc đã nằm trong buffer không thể thu hồi từ thiết bị người nhận.
- Khi bắt đầu xóa file, trạng thái Deleting làm mọi share ngừng phục vụ. Xóa metadata sẽ xóa các share liên quan trong cùng transaction để bảo toàn FK.

Trang public bỏ qua cookie JWT cũ/hết hiệu lực; quyền đọc phụ thuộc token chia sẻ. Việc này không mở quyền vào các API quản lý tệp thông thường, vốn vẫn yêu cầu đăng nhập.

## API

| API | Quyền | Kết quả |
| --- | --- | --- |
| POST `/api/files/{fileId}/shares` với `{expiresInHours:24}` | Chủ tệp | 201, `{id,path,createdAt,expiresAt}` |
| GET `/api/files/{fileId}/shares` | Chủ tệp | Danh sách `{id,createdAt,expiresAt,status}`; không trả token |
| DELETE `/api/files/{fileId}/shares/{id}` | Chủ tệp | 204; gọi lặp lại vẫn thành công |
| GET `/s/{token}` | Public | Trang nhận tệp |
| GET `/api/public/shares/{token}` | Token còn hiệu lực | Metadata public |
| GET/HEAD `/api/public/shares/{token}/stream` | Token còn hiệu lực | Resource, hỗ trợ Range/206 |
| GET/HEAD `/api/public/shares/{token}/download` | Token còn hiệu lực | Attachment |

Token sai/hết hạn/đã thu hồi hoặc file không còn phục vụ trả 404. API tạo/thu hồi với tài khoản khác trả 404; không đăng nhập trả 401. TTL sai trả 400, đạt giới hạn link trả 409.

## Nâng cấp và vận hành

Với SQL Server hiện có, chạy [002-file-sharing.sql](migrations/002-file-sharing.sql) sau migration 001 trong môi trường bảo trì và kiểm chứng trên bản sao trước khi triển khai. Dùng `HIBERNATE_DDL_AUTO=validate` sau migration. Dev/database trống có thể dùng ddl-auto update/create theo cấu hình dự án.

`SHARE_DEFAULT_HOURS` mặc định 24; `SHARE_MAX_HOURS` mặc định 720. Dùng HTTPS khi công khai link. Cấu hình proxy/application access logs để che token ở `/s/...` và `/api/public/shares/...`; URL là bí mật truy cập, không đưa vào analytics hoặc log payload. Áp dụng giới hạn lưu lượng public tại gateway phù hợp với Range streaming.

Link sharing hiện áp dụng cho **từng tệp**, gồm video/âm thanh/hình ảnh/tài liệu. Chia sẻ cả thư mục, đặt mật khẩu và thống kê lượt xem là các mở rộng riêng.

## Kiểm chứng

Ngày 26/10/2026: suite backend với profile `isolated-tests` ghi nhận 99 test, **91 qua, 8 skipped**, không failure/error. Tám test skipped cần RabbitMQ/Redis thật; các test Share service/HTTP đều chạy. **20 test JavaScript đều qua**. Chrome kiểm tra dropdown tạo link, sao chép (clipboard giả lập trong harness), thu hồi, mở ẩn danh, Range 206, phát/tua MP4 và layout 390 px; không có lỗi JavaScript hoặc vi phạm CSP.

Kiểm thử service gồm token chỉ lưu hash, hết hạn/thu hồi, quyền tài khoản khác, giới hạn TTL/số link, xóa file cùng FK và ưu tiên hiển thị link còn hoạt động trong lịch sử dài. Kiểm thử HTTP còn xác nhận cookie đăng nhập đã bị thu hồi không làm chặn một link public hợp lệ, đồng thời không mở API file riêng tư.

Log ở `target/test-logs/sharing-isolated.log`, `sharing-js.log`; ảnh/kết quả Chrome ở `target/share-browser/`. Chưa áp dụng migration 002 lên SQL Server thật trong workspace này.

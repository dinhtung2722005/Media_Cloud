# Rà soát tổng thể Media Cloud

Ngày kiểm tra: 26/10/2026. Rà soát working tree có RabbitMQ, Redis và các workflow đã cải thiện. Kiểm chứng bảo mật thực hiện trên server Spring Boot/H2/storage riêng trước khi bổ sung link sharing; không dùng dữ liệu riêng tư hoặc thử làm cạn tài nguyên hệ thống.

## Các vấn đề ưu tiên

| Mức | Vấn đề | Bằng chứng / điều kiện | Hướng xử lý |
| --- | --- | --- | --- |
| Cao | Worker FFmpeg đọc media ngoài phạm vi file đã được cấp quyền | Đã tái hiện bằng playlist do tài khoản A upload tham chiếu đường dẫn media của B; GET trực tiếp bị 404 nhưng worker tạo bản MP3 cho A thành công. Cần biết đường dẫn local và worker có quyền đọc; chỉ dùng fixture trong test. | Cô lập worker, chỉ mount input/output của lượt chạy; giới hạn demuxer/định dạng, không chỉ whitelist protocol. |
| Cao | Thư viện runtime có advisory DoS đã công bố | Spring Web 6.1.8, Tomcat 10.1.24, Security 6.3.0 theo classpath thực tế. Multipart đang bật. | Nâng BOM Spring Boot đồng bộ lên dòng còn được hỗ trợ và chạy regression; áp dụng giới hạn request/part ở gateway và container. |
| Cao | Multipart ẩn danh bị parse trước khi controller từ chối | POST multipart vào `/api/auth/login` đã parse file 1 MiB và request 128 file part khi chưa đăng nhập, sau đó trả 500; limiter trong controller không chạy. Chỉ gửi hai probe nhỏ, không thử gây DoS. | Chặn multipart trên auth/static routes trước DispatcherServlet, áp dụng ngân sách body theo route, nâng Tomcat và giới hạn part/header. |
| Trung bình | CSRF khi origin khác nhưng cùng site | Chrome từ cổng khác trên cùng host gửi multipart với cookie Lax, ghi chunk vào tài khoản nạn nhân thành công. Không chứng minh bypass Lax từ site bất kỳ. | Bảo vệ CSRF cho cookie-auth, kiểm tra Origin/Fetch Metadata phù hợp; giữ API Bearer dùng ngoài browser. |
| Trung bình | Giới hạn đăng nhập theo tài khoản phân biệt hoa/thường | Mô phỏng collation username không phân biệt hoa/thường: sau 19 lần sai cùng cách viết bị chặn, 25 biến thể casing vẫn được xử lý; giới hạn IP 60/phút vẫn còn. | Chuẩn hóa key limiter theo danh tính/collation tài khoản; kiểm chứng trên SQL Server thật. |
| Trung bình, phụ thuộc việc từng sử dụng | JWT secret và mật khẩu DB hardcoded còn trong lịch sử Git | Quét 14 commit; thấy literal tại `91421e00`, `src/main/resources/application.properties:4,21`. Giá trị được che trong báo cáo. Không thấy secret `.env` hiện tại trong các blob đã quét. | Thay khóa/mật khẩu nếu từng sử dụng, thu hồi phiên cũ; sau rotation mới xử lý lịch sử và bổ sung secret scanning. |
| Trung bình, triển khai | Cookie Secure mặc định false; SQL trustServerCertificate=true; ddl-auto mặc định update | Phù hợp dev cục bộ, chưa xác minh profile production/proxy. | Profile production: HTTPS, Secure cookie, chứng thư SQL đáng tin cậy, tài khoản DB tối thiểu và migration/validate. |
| Trung bình, hardening | Script CDN không SRI, SockJS dùng tag `@1`, app chính chưa có CSP riêng | Kiểm tra `static/index.html`; trang public share mới dùng tài nguyên cùng origin và CSP riêng. | Pin/self-host dependency, SRI và CSP phù hợp; HttpOnly không ngăn script cùng origin gọi API lấy dữ liệu. |

### Chi tiết ranh giới xử lý media

Đường dẫn input chính được FileStorage kiểm tra nằm trong root, và quyền sở hữu file được kiểm tra trước khi chạy FFmpeg. Tuy nhiên FFmpeg còn mở các tài nguyên được playlist tham chiếu. `-protocol_whitelist file,pipe` cho phép protocol local file; nó không giới hạn filesystem vào root của ứng dụng.

Probe service thật với FFmpeg portable 7.1 tạo được MP3 8.794 byte từ âm thanh fixture ngoài storage. Probe HTTP với hai tài khoản xác nhận A không đọc trực tiếp được file B (404), nhưng upload `.m3u8`, tạo job chuyển đổi (202), worker hoàn tất và A tải được bản chuyển đổi (200). Delivery được kích hoạt bằng endpoint chẩn đoán chỉ có trong harness thay cho RabbitMQ; các bước job/engine/finalization vẫn là service thật. Bài test giả định người tấn công biết đường dẫn media; không chứng minh đoán được UUID, đọc được mọi file văn bản hay thực thi lệnh tùy ý.

### Advisory thư viện

- [Apache Tomcat CVE-2025-48988 và CVE-2025-48976](https://tomcat.apache.org/security-10.html): phiên bản 10.1.24 nằm trong dải chịu ảnh hưởng của multipart có nhiều part/header. Không nên chỉ vá tới bản tối thiểu cũ vì còn các advisory sau đó.
- [Spring CVE-2024-38809](https://spring.io/security/cve-2024-38809/): Spring Web 6.1.8 thuộc dải ảnh hưởng khi parse ETag từ conditional headers; public static resources là bề mặt cần kiểm tra/giới hạn thêm. Không gửi payload gây cạn tài nguyên.
- Không kết luận RCE Tomcat chỉ từ version: các điều kiện DefaultServlet write/session persistence của một số CVE khác chưa được xác nhận ở dự án này.

## Những bảo vệ đã kiểm tra hoạt động

- Không đăng nhập bị 401; tài khoản khác không stream/download/xóa file, đọc folder/job hay upload vào folder riêng của người khác.
- Tên đường dẫn upload có traversal bị từ chối; nội dung HTML khi stream bị ép attachment, octet-stream, nosniff và CSP sandbox.
- YouTube URL localhost và URL dùng userinfo để giả host bị từ chối. ProcessBuilder dùng argument list, không dựng shell command từ dữ liệu người dùng.
- Logout thu hồi cả access và refresh của session; token cũ bị từ chối. JWT phân biệt access/refresh; secret cấu hình có kiểm tra độ dài key.
- WebSocket chỉ cho subscribe kênh progress của user, chặn SEND và destination khác; broker JSON ép kiểu message cố định. Đợt regression bảo mật 34 test qua, không failure/error/skipped.
- `.env` bị Git ignore; cổng RabbitMQ/Redis trong Compose bind localhost và có mật khẩu. Không có dữ liệu nhạy cảm thực được in trong báo cáo/probe output.

## Chức năng, hiệu năng và vận hành còn cần cải thiện

1. **Thumbnail video tranh băng thông:** [đã tái hiện](streaming-review.md), 24 thumbnail làm mở player ở 5 Mbps tăng từ 0,6 lên gần 30 giây. Dùng ảnh poster, tải theo viewport và ưu tiên player.
2. **Video dài trên mạng yếu:** [20 phút/200 thao tác](streaming-long-seek-review.md) tua không lỗi nhưng 1 Mbps khởi động khoảng 44 giây, p95 phát lại sau tua 4,82 giây. Cần rendition thấp/HLS nếu phục vụ mạng này.
3. **Lỗi HTTP chưa phân loại hết:** MediaType không hỗ trợ đang rơi vào generic 500 thay vì 415; kích thước request và lỗi framework cần mapping rõ ràng, giữ thông báo an toàn.
4. **SQL Server và nhiều node:** migration/FK/collation, crash recovery và shared volume vẫn cần xác nhận trên môi trường triển khai thật. H2 test không thay thế việc đó.
5. **Quan sát vận hành:** cần dashboard/alert tuổi outbox, DLQ, lease hết hạn, dung lượng và quota, cùng audit log hành động nhạy cảm. Không ghi token/share URL/password vào log.

## Lộ trình tính năng

Ưu tiên trước là các mục bảo mật cao và nghẽn thumbnail. **Chia sẻ link từng tệp** được bổ sung trong đợt làm tiếp theo, có thời hạn và thu hồi; xem [hợp đồng tính năng](file-sharing.md). Các bước tiếp theo nên là:

1. Quên/đổi mật khẩu, danh sách phiên và đăng xuất thiết bị; sau đó cân nhắc MFA.
2. Thùng rác có thời hạn, di chuyển/chọn nhiều tệp và tìm kiếm xuyên thư mục.
3. Lịch sử job, thử lại có chủ đích, thông báo kết quả và nhớ vị trí xem video.
4. Chia sẻ thư mục, mật khẩu link và nhật ký truy cập khi nhu cầu được chốt.

## Bằng chứng và giới hạn

`target/security-audit/http-results.json`, `media-boundary-results.json`, `history-results.json`, `regression.log` và harness tương ứng lưu kết quả tái lập (Git bỏ qua). Endpoint chẩn đoán, tài khoản, H2 và storage chỉ thuộc harness loopback; không được thêm vào source ứng dụng. Test rate limit dùng local fallback với H2 IGNORECASE để mô phỏng điều kiện collation. CSRF dùng hai origin cùng site. Không quét hệ thống ngoài dự án hoặc thực hiện tấn công làm sập dịch vụ.

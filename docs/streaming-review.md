# Kiểm tra độ mượt khi xem video

Ngày đo: 26/10/2026, Asia/Saigon.

Đã bổ sung [kiểm tra video 20 phút và tua liên tiếp](streaming-long-seek-review.md): 56 lượt tua tuần tự cùng 9 chuỗi kéo timeline, tổng 200 thao tác đặt thời gian.

**Kết luận:** stream một video với băng thông đủ hoạt động tốt trong phép thử. Điểm nghẽn rõ nhất là thư viện tự tải nhiều thumbnail bằng thẻ video: ở mạng giả lập 5 Mbps, 24 thumbnail làm thời gian bắt đầu phát tăng từ khoảng 0,6 giây lên 29,5–29,6 giây. Khi trì hoãn nguồn thumbnail trong harness A/B, cùng trang 24 mục bắt đầu phát lại trong 0,61 giây.

## Cách đo và phạm vi

- Server Spring Boot thật trên loopback, giữ nguyên controller, Spring Security, dịch vụ và filesystem; database H2/storage riêng trong `target/stream-audit/isolated`. Tạo tài khoản/tệp thử qua API; không dùng database hoặc video của người dùng.
- Chrome headless, HTTP cache tắt, viewport 1440×1000. Chạy `app.js` hiện tại; chặn CDN bên ngoài và thay bootstrap modal bằng shim trong harness để loại nhiễu tải thư viện. Đo từ lúc gọi `playMedia()` tới sự kiện `playing`, có tính API kiểm tra phiên mà hàm này gọi.
- Clip CC0 [flower.mp4 của MDN](https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4): 1.128.375 byte, 960×540, dài 5,055 giây, bitrate trung bình khoảng 1,79 Mbps. MP4 có `moov` ở đầu, trước `mdat`.
- Mạng: LAN không throttle; 5 Mbps download/80 ms latency; 1 Mbps download/150 ms latency qua Chrome DevTools. Mỗi lượt phát tới giây 1, tua tới 65% thời lượng, rồi chờ hết video.
- `seekMs` đo tới sự kiện `seeked`. Dừng đệm tính từ `waiting` tới `playing` sau lần phát đầu, loại khoảng đang seek; đây không phải phép đo cảm nhận trên mọi thiết bị. Frame lấy bằng [getVideoPlaybackQuality()](https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/getVideoPlaybackQuality).

## Kết quả phát và tua

| Trường hợp | Bắt đầu phát | Tua → seeked | Dừng đệm trong đoạn thử | Frame bị bỏ |
| --- | ---: | ---: | --- | ---: |
| LAN, khởi tạo decoder lần đầu | 1.022 ms | 28 ms | 0 | 1/94 |
| LAN, lượt tiếp theo | 191 ms | 26 ms | 0 | 0/95 |
| 5 Mbps, một video, lượt 1 | 600 ms | 26 ms | 0 | 0/95 |
| 5 Mbps, một video, lượt 2 | 583 ms | 29 ms | 0 | 0/95 |
| 1 Mbps, một video | 4.432 ms | 1.736 ms | 2 lần, tổng khoảng 1.433 ms | 0/94 |
| 5 Mbps, cùng tải 24 thumbnail | 29.627 ms | 36 ms | 0 sau khi đã phát | 0/95 |
| Lặp lại trường hợp 24 thumbnail | 29.506 ms | 30 ms | 0 sau khi đã phát | 0/95 |
| A/B: vẫn 24 mục, trì hoãn nguồn video thumbnail | 610 ms | 27 ms | 0 | 0/95 |

Lượt có thumbnail phát sinh **25 request stream**, truyền khoảng **19,25 MB** trong cửa sổ đo; lượt A/B chỉ phát sinh **1 request**, khoảng **1,13 MB**. Trì hoãn thumbnail chỉ được áp dụng trong harness để xác nhận nguyên nhân, chưa phải thay đổi trong ứng dụng.

## API stream

- Byte range đầu, cuối, open-ended và một byte đều trả **206**; kiểm tra byte thực tế và `Content-Range` đều đúng. MIME là `video/mp4`.
- Range vượt kích thước trả **416**; không đăng nhập trả **401**; HEAD trả kích thước đúng.
- 20 request tuần tự, mỗi request đọc 64 KiB: TTFB p50 khoảng **24–30 ms**, p95 khoảng **33 ms** trên loopback.
- 10 client đồng thời, tổng 50 request Range 1 MiB: p95 toàn request khoảng **140–253 ms**, throughput tổng **77–102 MiB/s** qua hai lượt đo. Đây là bài đọc Range ngắn với OS cache nóng, không phải chứng nhận tải nhiều người xem qua Internet hoặc SQL Server thật.

## Các điểm cần cải thiện

1. **Ưu tiên cao: thay thumbnail video bằng ảnh poster, chỉ tải stream khi mở video.** `fileCard()` trong `src/main/resources/static/app.js:548` gán `src` cho mọi video card, dù có `preload='metadata'`. Fragment `#t=0.5` còn đòi hỏi đọc frame. [preload là gợi ý cho trình duyệt](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/preload), không bảo đảm chỉ tải vài byte metadata. Đã xác nhận tác động bằng A/B; đây là nguyên nhân cần sửa đầu tiên.
2. **Giảm request trước khi mở trình phát.** `playMedia()` ở `app.js:745` chờ `GET /api/folders` để làm mới phiên trước khi gán nguồn. Trong lượt 24 thumbnail, riêng response này mất khoảng 7,2 giây ở phía client do cạnh tranh kết nối/băng thông; phép thử này không quy toàn bộ thời gian đó cho truy vấn SQL. Có thể xác thực bằng HEAD trên chính tài nguyên stream và ngừng các tải thumbnail trước khi mở trình phát.
3. **Chuẩn hóa file cho phát trên web.** Upload hiện giữ nguyên nội dung; tác vụ YouTube chọn `bestvideo+bestaudio/best` và merge MP4, chưa chuẩn hóa codec hoặc yêu cầu faststart rõ ràng. Cần kiểm tra codec/container và bố trí `moov` cho video thực tế. Theo [FFmpeg](https://ffmpeg.org/ffmpeg-formats.html), `-movflags +faststart` chuyển chỉ mục MP4 lên đầu để hỗ trợ bắt đầu phát tốt hơn. Clip thử đã có `moov` ở đầu; kết quả trên không chứng minh mọi MP4 upload đều như vậy.
4. **Mạng yếu/video bitrate cao:** hiện là progressive download qua HTTP Range, chưa có HLS/DASH hay lựa chọn bitrate thích ứng. Ở phép thử 1 Mbps thấp hơn bitrate clip, đã thấy chờ và dừng đệm. Nếu mục tiêu là video dài/1080p/4K trên mạng di động, cần các rendition bitrate và cơ chế đổi chất lượng phù hợp.

## Bằng chứng và giới hạn

`target/stream-audit/results.json`, `results-confirm.json`, `probe.log`, `probe-confirm.log` chứa số đo và sự kiện mạng; `probe.cjs` và `StreamAuditServer.java` là harness tái lập. Các artifact này được Git bỏ qua. Server và Chrome kiểm thử được dừng sau khi đo.

Phép đo trong báo cáo này dùng clip ngắn; kết quả video dài nằm trong báo cáo bổ sung ở trên. Chưa đo 1080p/4K, Safari/iPhone/Android, đường truyền Internet thật, reverse proxy/CDN hoặc SQL Server triển khai thực tế. Các số đo chưa đủ kết luận toàn hệ thống luôn mượt; chúng xác nhận nền tảng Range hoạt động và phát hiện nghẽn thumbnail có thể tái hiện.

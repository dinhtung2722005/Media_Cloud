# Video dài: hiệu suất khi tua liên tục

Ngày đo: 26/10/2026 (Asia/Saigon).

**Kết luận:** trên fixture 20 phút, tua xa và kéo timeline liên tiếp đều hoàn tất đúng vị trí, không ghi nhận lỗi HTTP. Ở mạng giả lập 5 Mbps, thời gian phát lại sau tua có trung vị 0,75 giây và p95 1,29 giây. Mạng 1 Mbps chậm rõ rệt: khởi động khoảng 44 giây, p95 phát lại sau tua 4,82 giây.

## Fixture và phương pháp

- MP4 **1.200,07 giây**, **268.195.292 byte** (268,2 MB), H.264/AAC, **960×540**, bitrate trung bình khoảng 1,79 Mbps. FFmpeg portable tạo video bằng cách lặp clip CC0 `flower.mp4` của MDN; đây là fixture tổng hợp dài 20 phút.
- Hai bản cùng dữ liệu media: `long-faststart.mp4` có `moov` ở đầu; `long-tail-index.mp4` có `moov` ở cuối. Chỉ mục `moov` dài **1.300.437 byte** ở cả hai bản.
- Giữ nguyên mã ứng dụng, API stream, xác thực JWT, kiểm tra quyền và filesystem. Dùng Spring Boot thật trên loopback, H2/storage tạm riêng; tạo tài khoản và upload hai fixture qua API.
- Chrome headless, HTTP cache tắt. Mỗi ca mở document và trình phát mới; chạy `playMedia()` hiện tại. CDN được chặn và modal dùng shim như lần đo trước. Đo một trình phát, không đồng thời tải các thumbnail khác.
- LAN không throttle; mạng giả lập 5 Mbps/80 ms latency; mạng giả lập 1 Mbps/150 ms latency.

Thực hiện **56 lượt tua tuần tự** qua các mốc 00:05, 00:30, 05:00, 10:00, 15:00, 19:50 và các mốc khác, có quay lại vị trí đã xem. Thêm **9 chuỗi kéo timeline**, mỗi chuỗi 16 vị trí cách nhau **120 ms**: tổng **144 thay đổi nhanh**, cộng thành **200 thao tác đặt thời gian**.

Mỗi lượt kiểm tra sự kiện `seeked`, khung hình thực tế qua `requestVideoFrameCallback` gần đúng mốc đích, rồi xác nhận đồng hồ video tăng thêm ít nhất 0,12 giây. Cột “phát lại” bao gồm khoảng xác nhận chuyển động này, không chỉ đo sự kiện `seeked`. P95 là percentile trong mẫu thử, không phải SLA.

## Tua tuần tự

| Môi trường | Số lượt | Mở video tới playing | Phát lại sau tua: p50 | p95 | Lớn nhất |
| --- | ---: | ---: | ---: | ---: | ---: |
| LAN, faststart | 20 | 1,15 s | 0,25 s | 0,33 s | 0,35 s |
| 5 Mbps, faststart | 20 | 2,79 s | 0,75 s | 1,29 s | 1,38 s |
| 1 Mbps, faststart | 10 | 43,89 s | 2,22 s | 4,82 s | 4,82 s |
| 5 Mbps, chỉ mục cuối file | 6 | 3,07 s | 0,98 s | 1,25 s | 1,25 s |

Không có lượt tua thất bại hoặc hiển thị sai mốc cuối trong các kiểm tra của harness. Chrome giữ một phần media buffer ngay cả khi HTTP cache tắt; kết quả gồm cả các vị trí chưa buffer và vị trí đã quay lại. Log ghi `wasBuffered` và danh sách khoảng buffer trước từng lượt để phân biệt.

Ở mạng 1 Mbps, `loadedmetadata` xuất hiện khoảng 11,9 giây sau khi mở trang thử, nhưng `playing` chỉ xuất hiện khoảng 44,1 giây trên đồng hồ trang. Chỉ mục dài 1,30 MB đã cần khoảng 10,4 giây truyền thuần ở 1 Mbps; sau đó trình duyệt còn tích lũy buffer. Băng thông này thấp hơn bitrate fixture, nên độ trễ không thể đánh giá chỉ từ thời gian API trả header.

## Kéo timeline liên tiếp

Sau chuỗi 16 thay đổi, video luôn quay về đúng vị trí cuối và tiếp tục phát. Thời gian lớn nhất tính từ **thay đổi cuối cùng** tới khi xác nhận phát lại:

| Môi trường | Số chuỗi | Phát lại chậm nhất sau lần kéo cuối |
| --- | ---: | ---: |
| LAN, faststart | 3 | 0,26 s |
| 5 Mbps, faststart | 3 | 1,13 s |
| 1 Mbps, faststart | 2 | 4,28 s |
| 5 Mbps, chỉ mục cuối | 1 | 1,23 s |

Chrome hủy các request Range cũ khi thay đổi vị trí. Trên 4 ca, harness ghi nhận **191 request stream**, **186 request bị hủy**, **0 HTTP 4xx/5xx**. Request bị hủy khi tua là hành vi bình thường với Range mở tới cuối file; không được xem là 186 lỗi phát.

Cuối mỗi ca còn một request prefetch đang chạy. Sau khi harness pause, bỏ `src` và gọi `load()` để giải phóng tài nguyên, số request còn hoạt động về 0 trong cửa sổ kiểm tra một giây. Đây là kiểm tra unload chủ động trong harness; không phải kết luận riêng về thời gian GC của handler đóng modal hiện tại.

## Phản hồi backend ở vị trí xa

36 request đọc **64 KiB** ở nhiều offset từ đầu tới 99% file đều trả **206**, đúng `Content-Range`, đúng byte so với file gốc trên ổ đĩa:

- First-byte p50: **35,9 ms**; p95: **53,4 ms**.
- p95 gần đầu file: **52,6 ms**; gần cuối: **52,1 ms**.

Trong phép đo này không thấy chi phí phản hồi tăng tuyến tính theo offset khi tua gần cuối video. Đây là phép đọc loopback với OS cache nóng và H2; SQL Server, storage/network thật có thể có thời gian khác.

## Đánh giá và hướng cải thiện

1. **Nền tảng Range đáp ứng việc tua video dài.** Vị trí xa và các lần kéo liên tục không làm player mắc kẹt trong các ca đã đo. Chỉ mục ở cuối vẫn hoạt động nhờ Range; trong mẫu này startup 3,07 giây so với 2,79 giây của faststart, không phải tải hết 268 MB mới phát.
2. **Mạng yếu vẫn là điểm nghẽn.** Video dài làm chỉ mục MP4 lớn hơn; ở 1 Mbps vừa chờ chỉ mục vừa chờ buffer. Nếu cần phục vụ mạng này, ưu tiên rendition bitrate thấp và phát thích ứng HLS/DASH thay vì chỉ truyền một bản 1,79 Mbps.
3. **Vẫn cần xử lý thumbnail tải đồng loạt.** Ca này cô lập một trình phát để đo tua. [Phép đo trước](streaming-review.md) đã xác nhận 24 thumbnail có thể đẩy thời gian mở video lên gần 30 giây ở 5 Mbps; vấn đề đó chưa được thay đổi trong đợt kiểm tra này.
4. **Giải phóng nguồn khi đóng trình phát** bằng pause/remove src/load là phương án cần kiểm tra khi cải thiện handler modal; chỉ remove DOM có thời điểm thu hồi phụ thuộc trình duyệt.

Phạm vi đo là khởi động và tua trên fixture dài 540p; chưa đại diện cho giải mã 1080p/4K, video có GOP/bitrate khác, điện thoại hoặc Internet/SQL Server thật. Phát liên tục trọn 20 phút là bài kiểm tra độ ổn định riêng.

## Bằng chứng và tái lập

Các artifact nằm trong `target/stream-long-audit/` (Git bỏ qua):

- `results.json`: từng lượt tua, sự kiện player, Range/header, request hủy và buffer.
- `summary.json`, `summary.log`: thống kê tổng hợp và vị trí atom MP4.
- `fixture.json`: thông tin codec/duration từ FFprobe.
- `probe.cjs`: harness HTTP/Chrome; `probe.log`: tiến độ và kết quả từng thao tác.
- `generate-faststart.log`, `generate-tail-index.log`: tạo fixture bằng FFmpeg 7.1 portable lấy từ artifact Bytedeco Maven Central, không thêm dependency vào ứng dụng.

Lệnh tạo fixture tương đương:

```text
ffmpeg -stream_loop -1 -i flower.mp4 -t 1200 -c copy -movflags +faststart long-faststart.mp4
ffmpeg -i long-faststart.mp4 -c copy long-tail-index.mp4
```

Server và Chrome kiểm thử được dừng sau khi hoàn tất; các fixture/log được giữ để đối chiếu.

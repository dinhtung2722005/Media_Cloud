Tính năng nổi bật (Core Features)
Upload Video Tối Ưu: Hỗ trợ cơ chế tải lên chia nhỏ (Chunked Upload) đối với các file dung lượng lớn. Tự động tiếp nhận và nối các mảnh file tuần tự, loại bỏ hoàn toàn xung đột khóa ghi file (I/O Lock) và nghẽn bộ nhớ RAM.

Xử Lý Ngầm Bất Đồng Bộ (Background Processing): Tích hợp Message Queue để điều phối các tác vụ tính toán nặng (như convert video, trích xuất metadata) xuống nền tảng worker, đảm bảo luồng Web chính (Main Thread) luôn phản hồi mượt mà.

Tối Ưu Trải Nghiệm Streaming: Hỗ trợ giao thức HTTP 206 Partial Content thông qua cấu hình ResourceRegion của Spring, cho phép người dùng tua video (seek) ngay lập tức mà không cần chờ tải toàn bộ tệp tin.

Giao Tiếp Thời Gian Thực (Real-time): Triển khai Spring WebSocket để đẩy liên tục trạng thái (% tiến độ tải lên, tiến trình xử lý file ngầm) từ Server về giao diện Client.

Bảo Mật Cao Cấp (Security): Hệ thống xác thực bằng JWT (JSON Web Token) kết hợp với Spring Security. Token được lưu trữ an toàn trong HttpOnly Cookie nhằm ngăn chặn triệt để các cuộc tấn công XSS.

Công nghệ sử dụng (Tech Stack)
Ngôn ngữ: Java (JDK 17+)

Framework: Spring Boot, Spring Data JPA, Spring Security 

Cơ sở dữ liệu: SQL Server (Quản lý siêu dữ liệu - Metadata)

Message Broker: Message Queue (JobRunr)

Real-time Communication: Spring WebSocket

Xác thực & Bảo mật: JWT, BCrypt

Kiến trúc Hệ thống (System Architecture)
Hệ thống được thiết kế theo mô hình Client-Server kết hợp xử lý bất đồng bộ:

RESTful API: Cung cấp các endpoint chuẩn hóa để Frontend giao tiếp (CRUD metadata, thao tác file).

Worker Node / Queue: Tách biệt tác vụ tải/chuyển đổi định dạng file ra khỏi API Node để giảm tải CPU/RAM.

Database Layer: Sử dụng mô hình danh sách kề (Adjacency List) trong SQL Server để tổ chức cấu trúc "Thư mục ảo" cho từng người dùng riêng biệt.
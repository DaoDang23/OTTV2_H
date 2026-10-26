# 08 — Bảo mật, tình huống lỗi và phương án xử lý

## 1. Mô hình đe dọa

Hệ thống nhận dữ liệu từ trình duyệt và nhận **mã bot do người dùng cung cấp**, vì thế rủi ro gồm gian lận lượt, truy cập phòng/bot trái phép, giả mạo kết quả, flood endpoint, chạy lệnh ngoài ý muốn, đọc file host và truy cập Internet từ sandbox.

**Nguyên tắc:** xác thực từng request, phân quyền từng resource, backend authoritative với trận online, rate-limit nơi thích hợp, dùng isolate cho mã bot, không lộ bí mật qua log/replay/error.

## 2. Nhóm tình huống và cách xử lý

| Mã | Tình huống | Nguy cơ | Ứng xử hệ thống mong muốn |
|---|---|---|---|
| S-01 | Người chơi gửi nước đi khi không đến lượt | Gian lận | Backend từ chối, không đổi state |
| S-02 | Gửi di chuyển chéo/xa hai ô | Sai luật | Validation từ chối; test unit tương ứng |
| S-03 | Gửi ăn quân cùng phe/cùng loại | Sai combat | Không cho phép move |
| S-04 | Client tự gửi `winner='A'` | Giả mạo kết quả | Backend tự tính `TARGET_REACHED`/`ANNIHILATION` |
| S-05 | Hai người cùng lúc gửi move | Race condition | Lock/version theo room, chốt một thứ tự hợp lệ |
| S-06 | Player mất mạng giữa lượt | Stale state | Cơ chế timeout/reconnect theo mode, đồng bộ snapshot |
| S-07 | Gói WebSocket bị lặp | UI lệch lượt | State version/correlation, bỏ gói cũ |
| S-08 | Người lạ biết room code | Truy cập trái phép | Session, quy tắc join và capacity |
| S-09 | Phòng đầy nhưng vẫn gửi join | Vượt capacity | Phát 409 hoặc lỗi nghiệp vụ, không tăng member |
| S-10 | Một player rời rồi vào lại | Ghost participant | Session recovery chính xác, không tăng ảo slot |
| S-11 | User khác tải replay riêng | Lộ dữ liệu | Ownership/participant check |
| S-12 | CSRF với session cookie | Thao tác ngoài ý muốn | Token CSRF + SameSite policy |
| S-13 | Truy cập admin tournament không có quyền | Thao tác trái phép | Role-based access control |
| S-14 | Submission quá lớn/gửi liên tục | DoS | Giới hạn byte, cooldown và daily limit |
| S-15 | Bot chạy vô hạn | Chiếm CPU | Timeout + isolate CPU/wall limits |
| S-16 | Bot dùng quá RAM/process | Tê liệt host | cgroup memory/process caps |
| S-17 | Bot đọc file host hoặc socket mạng | Lộ dữ liệu | Sandbox filesystem/network isolation |
| S-18 | Worker chết giữa match | Mất kết quả | Retry có idempotency; trạng thái fail rõ |
| S-19 | Queue bị backlog | Tăng độ trễ | Giám sát depth, backpressure, scale worker |
| S-20 | RabbitMQ/MinIO mất kết nối | Job không xử lý được | Retry có giới hạn, thông báo và metric |
| S-21 | DB commit thất bại sau event | Kết quả trùng/mất | Outbox hoặc xử lý kết quả idempotent |
| S-22 | Replay bị sửa/tải sai | Không kiểm chứng được | Checksum, object ownership, version |
| S-23 | Tunnel/proxy mất kết nối WebSocket | Mất realtime | Heartbeat/reconnect + re-fetch state |
| S-24 | Phiên đăng nhập hết hạn | 401/403 | UI yêu cầu đăng nhập lại, không mất dữ liệu đã lưu |
| S-25 | Người chơi mở hai tab cùng phiên | Double submit | Khóa theo user/room hoặc state version |
| S-26 | Log ghi token hoặc mật khẩu | Rò rỉ bí mật | Redact secret; phân quyền log |
| S-27 | Player đạt ô đích khi cùng lúc hết giờ | Tranh chấp kết quả | Thứ tự xử lý event do backend quy định, test race |

Bảng là **kế hoạch thiết kế/kiểm thử**. Không khẳng định mọi cơ chế đã được tích hợp đầy đủ vào source.

## 3. Phương án lỗi cho frontend

- Mã 401: yêu cầu đăng nhập lại; không tự tạo tài khoản phiên mới.
- Mã 403: hiển thị không đủ quyền; không lộ chi tiết của người dùng khác.
- Mã 409: tải lại state phòng nếu lượt/slot thay đổi.
- Mã 429: hiển thị cooldown hoặc giới hạn submission.
- Mất mạng: giữ snapshot gần nhất ở trạng thái read-only; khi online phải đồng bộ lại server.
- Lỗi backend 5xx: thông báo rõ, có retry an toàn, không tự ghi chiến thắng tại client.

## 4. Kiểm soát thay đổi và dữ liệu nhạy cảm

- Không commit `.env`, key MinIO, RabbitMQ password production, access token, dữ liệu người dùng thật.
- Không chạy lệnh `git add .` trước khi kiểm tra `git status` và `.gitignore`.
- Kiểm tra dependency và kiểm soát nguồn file upload; không thực thi mã user qua shell dựng từ chuỗi không escape.
- Kiểm tra log privacy; ẩn nội dung submission riêng tư khỏi endpoint công khai.

## 5. Liên hệ với thiết kế cũ

Bản Client–Server v1 đã đề cập handshake, framing, room, timeout, retry, reconnect, sai state, server crash. Trong kiến trúc web hiện tại, ý tưởng **kiểm tra state, authoritative server, timeout, khả năng khôi phục** vẫn còn giá trị; cú pháp `HELLO|...` và TCP line protocol không dùng trực tiếp cho web React.

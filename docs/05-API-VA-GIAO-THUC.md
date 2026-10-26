# 05 — REST API, xác thực và giao thức thời gian thực

## 1. Phạm vi

**Triển khai hiện tại không dùng giao thức TCP line-delimited** của bản thiết kế Version 1 (`HELLO|...`, `CHOICE|...`). Client web sử dụng HTTP REST `/api/*` và cập nhật realtime bằng WebSocket/STOMP. Hệ thống cần tuân nguyên tắc server authoritative: thông tin do client gửi chỉ là yêu cầu; việc thay đổi state là do server quyết định.

## 2. Nhóm endpoint tìm thấy trong controller

| Nhóm | Ví dụ route hiện diện | Ghi chú |
|---|---|---|
| Auth | `GET /api/auth/health`, `GET /api/auth/csrf`, `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me` | CSRF, cookie/token policy theo security config |
| Room | `POST /api/rooms/create`, `POST /api/rooms/join/{code}`, `GET /api/rooms/{code}`, `POST /api/rooms/{code}/leave` | Kiểm tra sức chứa/quyền phòng |
| Matchmaking | `POST /api/matchmaking/random`, `/join`, `/leave`, `/heartbeat` | Server xác thực yêu cầu, trạng thái hàng đợi |
| Challenge | `POST /api/challenge/send` | Chế độ thách đấu |
| Bot | `/api/bots`, `GET /api/bots/leaderboard`, `GET /api/bots/{id}` | Kiểm tra controller để biết đầy đủ method và body |
| Submission | `/api/bots/{botId}/submissions` | Mã nguồn, build status, giới hạn request |
| Match | `GET /api/matches/history`, `GET /api/matches/{publicId}`, replay/log routes | Quyền xem từng trận/replay |
| Profile | `/api/profile`, `POST /api/profile/matches` | Hồ sơ và thống kê |
| Ladder | `GET /api/ladder/rankings`, `POST /api/ladder/online`, `DELETE /api/ladder/online/{botId}` | Phân tách rating bot/PvP |
| Tournament | `/api/tournaments`, `/api/admin/tournaments/{tournamentId}` | Người dùng vs quyền admin |

Danh sách chỉ là **các route minh họa đã tìm thấy**, không phải OpenAPI tự sinh. Khi dùng với Postman phải kiểm tra chính xác method, request DTO, validation và authentication trong từng controller.

## 3. Quy ước request/response

- Dùng HTTPS trong production; nội dung JSON; phân biệt lỗi xác thực, quyền truy cập, validation và lỗi hệ thống.
- Thời gian/lượt do backend xác nhận; client không tin vào thời gian đồng hồ máy người dùng.
- Xử lý retry cho POST tạo resource bằng khóa idempotency nếu luồng hỗ trợ; tránh người dùng nhấn nút nhiều lần.
- Server không trả stack trace, mật khẩu, access token hoặc key object store trong lỗi.
- Các endpoint lấy replay phải kiểm tra người sở hữu/participant hoặc quyền công khai theo nghiệp vụ.

## 4. Xác thực và phiên

Thư mục `api/security/` có `SecurityConfiguration`, `TokenAuthenticationFilter`, `TokenSessionService`, `SameSiteCookieFilter`. Cơ chế xác thực có thể dùng session/cookie và token tại các luồng tương ứng. Phía client cần gửi cookie an toàn, lấy token CSRF khi backend yêu cầu và tránh lưu token dài hạn trong code nguồn. Biến môi trường `SESSION_COOKIE_SECURE` cần đúng khi đặt sau reverse proxy HTTPS.

## 5. WebSocket/STOMP

`RoomSession` và `WebSocketConfig` quản lý những kênh cập nhật realtime. Tùy endpoint được cấu hình, client kết nối qua `/ws` hoặc `/api/ws`, subscribe topic theo room và giải đấu. URL thực và destination cụ thể phải lấy từ `WebSocketConfig`, frontend subscribe code và proxy khi tích hợp.

### 5.1 Luồng đồng bộ phòng đề xuất

```text
A -> REST: tạo phòng
API -> A: mã phòng và snapshot
B -> REST: tham gia phòng
API -> WebSocket: broadcast thành viên/snapshot
A -> API: nước đi hợp lệ
API: kiểm tra lượt, quyền, game rules
API -> WebSocket: broadcast move + turn + kết quả nếu kết thúc
A/B: cập nhật UI từ state authoritative
```

Yêu cầu tính toàn vẹn: message mang room id, version/sequence state và thông tin tham chiếu nếu backend hỗ trợ; client nhận gói cũ phải bỏ qua, reconnect phải đồng bộ snapshot mới thay vì tự suy đoán nước đi thiếu.

## 6. Phân loại lỗi quan trọng

| Loại | Tình huống | Ứng xử UI |
|---|---|---|
| `400` | Tọa độ sai, body không hợp lệ | Hiển thị lý do hợp lệ, không sửa state |
| `401` | Chưa xác thực/phiên hết hạn | Yêu cầu đăng nhập lại |
| `403` | Người dùng không có quyền | Không tiết lộ dữ liệu phòng/bot |
| `404` | Phòng/trận không tồn tại | Quay về danh sách phù hợp |
| `409` | Phòng đầy, conflict lượt, trạng thái đổi | Tải lại snapshot; tránh thao tác lặp |
| `429` | Gửi submission quá nhiều | Hiển thị cooldown |
| `5xx` | API/DB/broker lỗi | Có retry có kiểm soát, log request ID |

Các mã HTTP trên là **hướng dẫn xử lý**; không khẳng định mọi tình huống hiện đã map đúng mã như bảng. Đối chiếu `ApiExceptionHandler` và kiểm thử tích hợp.

## 7. Kiểm thử giao thức

- Không cho guest điều khiển quân của host.
- Hai client gửi cùng lúc: chỉ một state chuyển hợp lệ, không double move.
- Reconnect không làm nhân đôi rating hoặc tạo bản ghi trận trùng.
- Gửi room code đoán ngẫu nhiên không truy cập được thông tin riêng tư.
- Test handshake, origin, CSRF và role admin.
- Test server gửi winner reason cho **I1/A9** và **ANNIHILATION**, đồng bộ cho cả hai client.

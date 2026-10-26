# 01 — Tổng quan dự án và đặc tả yêu cầu

## 1. Thông tin chung

- **Tên hệ thống:** OTTv2 — Battle Code Arena.
- **Loại sản phẩm:** game chiến thuật Oẳn Tù Tì theo lượt trên bàn cờ 9×9, có chế độ người–người và hệ sinh thái bot lập trình.
- **Mô hình:** ứng dụng web Client–Server; backend kiểm soát dữ liệu nghiệp vụ và luồng thi đấu; engine xử lý luật.
- **Đối tượng:** người chơi, lập trình viên bot, người tổ chức giải đấu và quản trị vận hành.
- **Phạm vi đối chiếu:** mã nguồn dự án được cung cấp ngày 26/10/2026; tài liệu không thay thế log kiểm thử hay kết quả triển khai thực tế.

## 2. Bối cảnh và vấn đề

Oẳn Tù Tì truyền thống giải quyết một vòng chọn Búa/Bao/Kéo. OTTv2 mở rộng quan hệ khắc chế thành chiến thuật trên bàn 9×9: người chơi phải điều khiển nhiều quân, bảo vệ ô đích, tính hướng tiến quân và chọn pha giao tranh có lợi. Khi đưa trò chơi lên Internet, hệ thống còn phải xử lý đồng bộ lượt, phòng chơi, lưu lịch sử, người dùng đăng nhập đồng thời và tính toàn vẹn kết quả. Ngoài PvP, bài toán thi đấu bot bổ sung hàng đợi tác vụ, biên dịch, cô lập mã không tin cậy và lưu replay để kiểm chứng.

Các báo cáo trước đây mô tả một nguyên mẫu TCP socket với thông điệp `HELLO|...`, `CHOICE|...`. Đây là **lịch sử thiết kế**, không phải giao thức mặc định của web hiện tại; triển khai hiện tại sử dụng REST qua HTTP và thông điệp WebSocket/STOMP.

## 3. Mục tiêu

1. Cung cấp giao diện bàn cờ trực quan, thao tác theo lượt, phản hồi nước đi hợp lệ và thông báo kết thúc trận.
2. Hỗ trợ đăng ký/đăng nhập, hồ sơ và thống kê, các chế độ chơi/ghép trận tùy điều kiện backend.
3. Tách logic trò chơi thành engine tái sử dụng, có khả năng ghi sự kiện và replay.
4. Hỗ trợ gửi mã nguồn bot, biên dịch và thi đấu trong môi trường cách ly.
5. Quản lý bảng xếp hạng, lịch sử và giải đấu; không trộn điểm PvP với điểm bot.
6. Thiết kế khả năng theo dõi hoạt động, kiểm thử tải và xử lý lỗi mạng.

## 4. Yêu cầu chức năng

| Mã | Yêu cầu | Ưu tiên | Thành phần đối chiếu |
|---|---|---|---|
| FR-01 | Đăng ký, đăng nhập, lấy thông tin người dùng hiện tại | Cao | `api/auth/`, `web/src/App.jsx` |
| FR-02 | Xem/cập nhật hồ sơ và thống kê theo API hỗ trợ | Cao | `api/profile/`, `web/src/components/Profile.jsx` |
| FR-03 | Hiển thị bàn cờ 9×9, quân Đỏ/Xanh, ô đích | Cao | `InteractiveBoard.jsx`, `gameRules.js` |
| FR-04 | Chọn quân của lượt hiện tại và liệt kê vị trí hợp lệ | Cao | `getValidMoves`, `MoveCommand` |
| FR-05 | Ăn quân theo Búa–Bao–Kéo và áp dụng điều kiện thắng | Cao | `canBeat`, `RpsCombatResolver`, `WinCondition` |
| FR-06 | Tạo/tham gia/rời phòng, ghép người chơi | Cao | `api/room/`, `RoomGameModal.jsx` |
| FR-07 | Đồng bộ trạng thái và sự kiện trận qua server | Cao | `WebSocketConfig`, `RoomSession` |
| FR-08 | Lưu lịch sử trận/replay và cho phép xem lại | Trung bình | `api/match/`, `ReplayModal.jsx` |
| FR-09 | Tạo bot, gửi bài và quản lý kết quả biên dịch | Trung bình | `api/bot`, `api/submission`, `worker/` |
| FR-10 | Xếp hạng/giải đấu/bảng đấu | Trung bình | `api/ladder`, `api/tournament` |
| FR-11 | Giới hạn quyền truy cập và xác thực endpoint | Cao | `api/security/` |
| FR-12 | Ghi metric, kiểm thử tải, khởi chạy nhiều dịch vụ | Trung bình | `monitoring/`, `loadtest/`, `compose.yaml` |

Một mục được ghi nhận có lớp hoặc route tương ứng **không đồng nghĩa** toàn bộ luồng đã được kiểm thử end-to-end.

## 5. Yêu cầu phi chức năng

- **Tính nhất quán:** backend kiểm tra quyền thao tác và trạng thái; client không được tự tuyên bố kết quả chính thức của trận online.
- **Độ tin cậy:** tránh cập nhật lặp trận/rating, xử lý mất kết nối, lỗi worker và queue retry.
- **Bảo mật:** kiểm tra truy cập, CSRF/cookie và nguồn gốc WebSocket; mã bot không tin cậy phải chạy trong sandbox Linux.
- **Hiệu năng:** giới hạn thời gian xử lý tác vụ, quan sát độ trễ API và độ dài queue; kiểm chứng bằng k6/Prometheus thay vì suy diễn.
- **Khả năng bảo trì:** frontend, API, engine, worker, infra tách module; quản lý schema bằng Flyway.
- **Khả năng triển khai:** chạy local để phát triển, Docker Compose cho hạ tầng và YAML Kubernetes cho một số luồng worker.

**Chỉ tiêu lịch sử:** báo cáo đề xuất ban đầu nêu bài toán ~1.000 kết nối trên laptop 8 GB và Cloudflare Tunnel. Đây là mục tiêu kiểm thử/triển khai, **chưa được coi là kết quả đo đã đạt**.

## 6. Vai trò và tình huống sử dụng

| Vai trò | Tác vụ đặc trưng | Tiền điều kiện | Hậu điều kiện |
|---|---|---|---|
| Khách | Xem trang, mở chế độ phù hợp | Không yêu cầu đăng nhập cho những trang public | Không sửa dữ liệu cá nhân |
| Người chơi | Đăng nhập, vào phòng, chọn quân, di chuyển | Có phiên hợp lệ; đến lượt của mình | Server xác nhận state mới hoặc trả lỗi |
| Chủ phòng | Tạo phòng, mời người chơi, thoát phòng | Đăng nhập, không vi phạm quy tắc phòng | Phòng đổi trạng thái phù hợp |
| Tác giả bot | Gửi code, chọn bản bot để thi đấu | Có quyền sở hữu bot; request trong giới hạn | Có submission/result và trạng thái biên dịch |
| Quản trị giải | Tạo/duyệt/bắt đầu/giải quyết sự cố | Quyền quản trị | Trạng thái bracket/match được ghi nhận |

## 7. Ranh giới và giả định

- Luật đang mô tả ưu tiên **bản game 9×9 trên giao diện mà người dùng xác nhận**. Phần cơ chế nâng cao của Java engine (ô cản, hiệu ứng đặc biệt) được ghi riêng, không áp dụng mặc định cho mọi phòng PvP.
- Nước đi UI hiện tại là **một ô theo bốn hướng**. Tài liệu OTT cũ mô tả tám hướng không còn là nguồn đúng cho bản giao diện này.
- Cần test tích hợp để đảm bảo quy tắc ăn quân trên frontend và Java engine thống nhất; xem mục rủi ro trong tài liệu số 03.

## 8. Tiêu chí chấp nhận tối thiểu

1. Hai phe mỗi phe khởi tạo đúng 9 quân và hai ô đích đúng tọa độ.
2. Đến lượt Xanh chỉ điều khiển quân Xanh, tương tự với Đỏ.
3. Búa ăn Kéo, Kéo ăn Bao, Bao ăn Búa; không ăn quân cùng phe hoặc quân ngang loại.
4. Bất kỳ quân Xanh nào vào **I1 (ô xanh)** ⇒ Xanh thắng; bất kỳ quân Đỏ nào vào **A9 (ô đỏ)** ⇒ Đỏ thắng.
5. Khi một phe không còn quân nào, phe còn quân thắng.
6. Người chơi không thể gửi nước đi trái lượt hoặc sửa trận của phòng khác.
7. Kết quả, lịch sử và replay thể hiện nhất quán với trạng thái authoritative.

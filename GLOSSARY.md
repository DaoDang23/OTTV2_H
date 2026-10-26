# Từ điển thuật ngữ OTTV2

Tài liệu định nghĩa thống nhất thuật ngữ giữa frontend, backend, engine, worker và báo cáo đồ án. Khi mô tả giao thức hoặc quy tắc kỹ thuật, ưu tiên tên định danh đang dùng trong source code.

## 1. Game và bàn cờ

| Thuật ngữ | Giải thích |
|---|---|
| `Board` | Bàn cờ vuông 9×9; tọa độ code `(row, col)` từ 0 đến 8. |
| `Cell` | Một ô trên bàn, có vị trí, loại ô và có thể có hiệu ứng. |
| `Piece` | Quân Búa (`ROCK`), Bao (`PAPER`), Kéo (`SCISSORS`). |
| `Team A`, `Team B` | Hai phía thi đấu; trong UI mặc định A màu xanh phía dưới, B màu đỏ phía trên. |
| `I1` | **Ô đích màu xanh** phía A cần đạt tới; tọa độ `(0, 8)` trong `web/src/utils/gameRules.js`. Xanh vào I1 là thắng. |
| `A9` | **Ô đích màu đỏ** phía B cần đạt tới; tọa độ `(8, 0)`. Đỏ vào A9 là thắng. |
| `Legal move` | Nước đi hợp lệ do game rule/state cho phép; các lớp có thể khác nhau giữa engine và UI. |
| `CombatResolver` | Thành phần phân xử va chạm quân; `RpsCombatResolver` áp dụng quan hệ Búa–Bao–Kéo. |
| `WinCondition` | Điều kiện kết thúc trận: đúng phe đến ô đích cùng màu, ăn hết quân đối phương hoặc quy tắc riêng của chế độ như giới hạn lượt. |
| `GameState` | Trạng thái bàn, quân, lượt và phase tại một thời điểm. |
| `GameEvent` | Sự kiện do engine phát ra sau thao tác: di chuyển, bắt quân, kết thúc ván… |
| `Replay` | Dữ liệu tái hiện diễn biến, lưu ở dạng JSON hoặc blob tùy luồng. |
| `Seed` | Giá trị khởi tạo bộ sinh ngẫu nhiên, dùng cho map và tái lập điều kiện trận. |
| `Map symmetry` | Tính đối xứng tâm nhằm tạo bố cục công bằng cho hai bên. |
| `Fischer clock` | Đồng hồ có thời gian ban đầu và cộng thêm sau mỗi nước đi. |

**Cần phân biệt:** `web/src/utils/gameRules.js` triển khai nước đi bốn hướng trực giao. `engine/` cũng có `OrthogonalOneStep`. Bản diễn giải “tám hướng như vua” của phiên bản OTT trước không phải mặc định của source đang tài liệu hóa.

## 2. Người chơi và chế độ

| Thuật ngữ | Giải thích |
|---|---|
| `PvP` | Đấu người với người, gồm phòng trực tiếp và ghép trận. |
| `Room` | Phòng chơi có mã định danh, host và guest. |
| `RoomSession` | Trạng thái phòng trên server; không được tin room code hoặc trạng thái do client tự khai báo. |
| `Matchmaking` | Ghép cặp người chơi theo logic hàng đợi/điều kiện của backend. |
| `Sparring AI` | Bot luyện tập nhằm phục vụ đấu tập hoặc phương án dự phòng theo luồng sản phẩm. |
| `ELO` | Điểm đánh giá năng lực theo kết quả thi đấu. |
| `pvpElo` | Điểm gắn với thi đấu PvP. |
| `botElo` | Điểm gắn với bot/ladder. Hai hệ thống không được trộn khi xếp hạng. |
| `Ladder` | Thi đấu/xếp hạng bot theo mùa và các cửa sổ hoạt động. |
| `Tournament` | Giải đấu có đăng ký, nhánh đấu, seed, trận và kết quả. |
| `Bracket` | Cây nhánh đấu loại trực tiếp. |

## 3. Client–Server và dữ liệu

| Thuật ngữ | Giải thích |
|---|---|
| `REST` | API HTTP trao đổi dữ liệu tài khoản, phòng, bot, giải đấu… |
| `STOMP` | Giao thức message dùng qua WebSocket cho sự kiện phòng/giải đấu. |
| `Topic` | Kênh subscribe nhận sự kiện server broadcast, ví dụ `/topic/room/{code}`. |
| `CSRF` | Cơ chế bảo vệ yêu cầu thay đổi dữ liệu của phiên cookie. |
| `Token authentication` | Cơ chế bổ sung hỗ trợ xác thực request bằng token; không được công khai token. |
| `Flyway` | Quản lý version schema PostgreSQL thông qua script `V*.sql`. |
| `Outbox` | Ghi sự kiện ra hàng đợi trung gian trong giao dịch DB nhằm giảm sai lệch DB/MQ. |
| `Idempotency` | Xử lý lặp thông điệp mà không tạo hiệu ứng trùng. |
| `RabbitMQ` | Message broker xử lý compile/match/result. |
| `Redis` | Bộ nhớ dữ liệu nhanh dùng cho một số luồng presence/ladder. |
| `MinIO` | Lưu trữ object cho mã nguồn, artifact bot, replay/log. |
| `Sandbox` | Môi trường giới hạn quyền khi chạy mã bot không tin cậy. |
| `isolate` | Công cụ sandbox dành cho Linux được tích hợp trong worker. |
| `Prometheus/Grafana` | Thu thập metric và dashboard theo dõi. |

## 4. Trạng thái triển khai và tài liệu

- **Hiện diện trong code:** đã tìm thấy lớp, endpoint, migration, cấu hình hoặc test tương ứng; chưa mặc nhiên được coi là đã kiểm thử end-to-end.
- **Thiết kế/đề xuất:** mục tiêu hoặc giải pháp nêu ở tài liệu dự án nhưng không đủ căn cứ xác nhận đã triển khai.
- **Kiểm chứng:** có kết quả chạy test, log hoặc benchmark và có điều kiện đo được công bố rõ.
- **ADR:** Architecture Decision Record, ghi lại bối cảnh, lựa chọn và hệ quả của quyết định thiết kế.

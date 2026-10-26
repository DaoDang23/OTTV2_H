# 02 — Kiến trúc hệ thống OTTv2

## 1. Quan điểm kiến trúc

OTT v2 áp dụng kiến trúc nhiều thành phần: **React/Vite client → Spring Boot API → dữ liệu/dịch vụ hạ tầng**, đồng thời tách **engine** thực thi luật chơi và **worker** chạy bot trong sandbox. Mục tiêu không phải chia microservice bằng mọi giá mà là tách luồng phục vụ HTTP/WebSocket khỏi tác vụ biên dịch/thi đấu có thể tốn CPU và không đáng tin cậy.

## 2. Sơ đồ triển khai logic

```text
Trình duyệt (React / Vite)
  ├── HTTP(S) REST /api/* ───────────┐
  └── WS/STOMP (/ws, /api/ws) ───────┤
                                     ▼
                         Java Spring Boot API
                       ┌────────┼─────────────┐
                       ▼        ▼             ▼
                  PostgreSQL  Redis         RabbitMQ
                    +Flyway  presence      job/result
                       │                      │
                       ▼                      ▼
                     MinIO              Worker services
                  source/replay       compile / match jobs
                                           │
                                      Java game engine
                                           │
                                      Linux isolate
```

API chịu trách nhiệm xác thực, dữ liệu và orchestration; engine đánh giá trạng thái luật; worker xử lý mã bot trong tiến trình độc lập. Prometheus/Grafana giám sát cả API và worker.

## 3. Các thành phần nguồn

| Đường dẫn | Vai trò | Điểm vào/ghi chú |
|---|---|---|
| `web/` | SPA React 19, Vite 6, router, các màn hình game | `src/main.jsx`, `src/App.jsx` |
| `api/` | REST, WebSocket/STOMP, security, service, repository, migration | `api/pom.xml`, `api/src/main/java/ottv2/api` |
| `engine/` | Mô hình `Board`, `Piece`, `GameState`; Move, Combat, WinCondition, Replay | `engine/src/main/java/ottv2/engine` |
| `worker/` | Biên dịch bot, cô lập và thực hiện trận tự động | `worker/README.md` |
| `src/` | Nguồn liên quan module Maven gốc | xem `pom.xml` |
| `ottv2/docs/` | Các bản đặc tả và quyết định thiết kế trước đây | tham chiếu, không thay thế code |
| `loadtest/` | Tập lệnh k6 và template báo cáo tải | `loadtest/k6/ottv2.js` |
| `monitoring/` | Prometheus và dashboard Grafana | `monitoring/prometheus.yml` |
| `k8s/` | Manifest mở rộng match worker | `k8s/match-worker-scaledobject.yaml` |

## 4. Phân tầng backend

- **Controller/API boundary:** nhận request, phân quyền và validation dữ liệu ở cổng API.
- **Application service:** xác thực nghiệp vụ, vòng đời bot, giải đấu, room và lịch sử trận.
- **Persistence:** PostgreSQL quản lý đối tượng bền vững; Flyway migration theo version.
- **Message broker:** RabbitMQ tách phát lệnh compile/match với xử lý worker và sự kiện kết quả.
- **Object store:** MinIO lưu mã nguồn, artifact, replay và log dưới khóa object.
- **Cache/trạng thái tạm:** Redis dùng cho các tác vụ presence/ranking tùy luồng.

## 5. Luồng phòng PvP

1. Người chơi đăng nhập để có phiên hợp lệ.
2. Client tạo hoặc tham gia phòng bằng API tại `/api/rooms/...`; backend xác nhận quyền, sức chứa và tình trạng phòng.
3. Client subscribe topic phòng và nhận snapshot/sự kiện mới qua WebSocket/STOMP.
4. Người đang có lượt gửi yêu cầu di chuyển; backend kiểm tra nguồn gửi và trạng thái.
5. Sau khi nước đi được chấp nhận, trạng thái phòng đồng bộ cho các client. Khi đạt ô đích/tiêu diệt đối thủ, phiên kết thúc và kết quả được lưu nếu có luồng persist tương ứng.
6. Tình huống disconnect, join lại, race condition cần được test; không coi state frontend là nguồn chuẩn của trận online.

## 6. Luồng bot tự động

```text
Người dùng gửi code → Submission API → MinIO lưu mã nguồn
             → Queue compile → Compile Worker → artifact + trạng thái
             → Đặt lịch trận → Queue match → Match Worker
             → Sandbox isolate chạy bot → Game Engine xử lý lượt
             → Replay/logs trên MinIO → Result event → API cập nhật DB
```

Các tên queue cụ thể được mô tả trong `worker/README.md`. Worker không cần nhận trực tiếp credentials database để tính trận.

## 7. Quy tắc nhất quán

- Khi triển khai phòng online, **phê duyệt nước đi và kết quả cần nằm ở phía server**.
- Một message/result có thể được gửi lại: API phải xử lý lặp để không tăng rating/lưu lịch sử hai lần.
- `Replay` phải có thông tin đủ để tái hiện tiến trình; không chỉ lưu chữ “Xanh thắng”.
- Đồng bộ thứ tự lượt và sự kiện; client reconnect cần nhận state mới nhất, không cộng dồn event cũ không kiểm soát.
- Không hiển thị dữ liệu nhạy cảm như token, mật khẩu, artifact của người khác.

## 8. Triển khai và quan sát

- Phát triển local: backend cổng 8080, frontend Vite cổng 5173.
- Docker Compose phục vụ PostgreSQL, RabbitMQ, Redis, MinIO và giám sát theo cấu hình file thực tế.
- Tác vụ chạy bot không tin cậy cần Linux + isolate đã cấu hình cgroup/network/filesystem.
- `k8s/` là manifest cho một phần triển khai; không mặc nhiên có cấu hình đầy đủ để chạy production.
- Quan sát API latency, status code, queue depth, thời gian match, lỗi sandbox và mức sử dụng tài nguyên.

## 9. Quyết định và trade-off

| Quyết định | Lợi ích | Đánh đổi/rủi ro |
|---|---|---|
| REST + WebSocket/STOMP | Hợp web, cập nhật realtime | Cần reconnect và chống stale state |
| Java Spring Boot + engine riêng | Tái sử dụng logic, phân lớp rõ | Cần đảm bảo frontend–engine đồng nhất |
| RabbitMQ + worker | Tránh CPU nặng chặn request | Queue backlog, retry, idempotency |
| PostgreSQL + Flyway | Schema có phiên bản, dữ liệu bền | Migration cần backward compatibility |
| MinIO | Tách binary khỏi DB | Kiểm soát object ownership/retention |
| Isolate Linux | Giảm rủi ro mã bot | Cấu hình hệ điều hành phức tạp |

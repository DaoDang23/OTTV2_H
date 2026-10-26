# OTTV2 — Battle Code Arena

**Hệ thống thi đấu Oẳn Tù Tì chiến thuật trên bàn cờ 9×9, hỗ trợ PvP trực tuyến và thi đấu bot lập trình.**

**Mã nguồn:** [DaoDang23/OTTV2_H](https://github.com/DaoDang23/OTTV2_H) — tài liệu này dành cho dự án mã nguồn, không yêu cầu sử dụng các repository cũ.

OTTV2 phát triển nguyên lý Búa – Bao – Kéo thành trò chơi chiến thuật theo lượt. Dự án có frontend React/Vite, backend Java/Spring Boot, thư viện game engine, worker biên dịch/chạy bot và hạ tầng phục vụ tài khoản, giải đấu, bảng xếp hạng, replay và giám sát.

> **Phạm vi tài liệu:** phản ánh mã nguồn được cung cấp ngày 26/10/2026. Các khả năng ở mức thiết kế/đề xuất được phân biệt với phần mã nguồn đang hiện diện. Tài liệu này không xác nhận đã đo đạt mục tiêu 1.000/3.000 kết nối đồng thời hoặc triển khai sản xuất.

## 1. Chức năng chính

| Nhóm | Chức năng | Nơi triển khai |
|---|---|---|
| Tài khoản | Đăng ký, đăng nhập, phiên xác thực, hồ sơ và thống kê | `api/auth`, `api/profile`, `web/src/components/Profile.jsx` |
| PvP | Phòng chơi hai người, ghép trận, nước đi đồng bộ qua REST/WebSocket | `api/room`, `web/src/components/RoomGameModal.jsx` |
| Bot | Quản lý bot, gửi mã nguồn, tạo bản biên dịch, thi đấu bot | `api/bot`, `api/submission`, `worker/` |
| Engine | Trạng thái bàn cờ, di chuyển, giao tranh, chiến thắng, replay | `engine/` |
| Thi đấu xếp hạng | Bot ladder và rating; phân biệt với rating PvP | `api/ladder`, `web/src/components/Leaderboard.jsx` |
| Giải đấu | Quản trị, đăng ký/khởi chạy, bracket và cập nhật kết quả | `api/tournament`, `web/src/components/Tournaments.jsx` |
| Vận hành | PostgreSQL, RabbitMQ, Redis, MinIO, Prometheus, Grafana | `compose.yaml`, `monitoring/`, `loadtest/` |

**Điều kiện thắng chính thức:** **Xanh đến ô xanh I1** hoặc **Đỏ đến ô đỏ A9** thì thắng ngay; **ăn hết quân đối phương** cũng thắng. Cách ăn quân là **Búa ăn Kéo, Kéo ăn Bao, Bao ăn Búa**. Trong mã frontend `web/src/utils/gameRules.js`, quân cờ đi **một ô theo bốn hướng trực giao**; không tự động suy ra di chuyển tám hướng từ các phiên bản OTT cũ. Xem [Luật chơi và engine](docs/03-LUAT-CHOI-VA-ENGINE.md).

## 2. Sơ đồ thành phần

```text
Browser — React + Vite
   │   REST /api; STOMP WebSocket /api/ws hoặc /ws
   ▼
Spring Boot API ─── PostgreSQL (Flyway)
   │         ├──── Redis (cache / ladder)
   │         ├──── MinIO (source/artifact/replay)
   │         └──── RabbitMQ (compile/match/result queues)
   │                         │
   └── quản lý nghiệp vụ     ▼
                        Compile Worker / Match Worker
                                   │
                            Java Game Engine
                                   │
                         Bot sandbox trên Linux
```

Cấu trúc Maven đa module: `engine/`, `api/`, `worker/`. Web đặt tại `web/` và chạy độc lập bằng Node.js. Thư mục `ottv2/docs/` chứa các tài liệu quyết định thiết kế từ giai đoạn trước, không phải một module Maven.

## 3. Yêu cầu môi trường

- **Java 21** theo `pom.xml`; Maven Wrapper có sẵn (`mvnw`, `mvnw.cmd`).
- **Node.js và npm** tương thích với Vite 6 (khuyến nghị Node.js phiên bản LTS hiện hành, ít nhất Node 18).
- **Docker và Docker Compose** cho PostgreSQL 16, RabbitMQ, Redis, MinIO và dịch vụ monitoring.
- **Linux hoặc Windows + WSL2** cho worker sử dụng `isolate`; không thực thi mã bot không tin cậy bằng chế độ `local`.
- Git và quyền cài đặt các gói cần thiết trên môi trường chạy worker.

## 4. Chạy dự án trên máy cá nhân

### Bước 1. Chuẩn bị hạ tầng

Từ thư mục gốc:

```powershell
docker compose up -d postgres rabbitmq redis minio
docker compose ps
```

Các giá trị trong `compose.yaml` chỉ là mặc định **phát triển cục bộ**. Không đưa các cổng quản trị hoặc mật khẩu mẫu ra Internet.

### Bước 2. Biên dịch và chạy backend

Windows PowerShell:

```powershell
.\mvnw.cmd -pl api -am spring-boot:run
```

Linux/WSL2:

```bash
./mvnw -pl api -am spring-boot:run
```

Backend mặc định sử dụng `http://localhost:8080`; endpoint kiểm tra nhanh: `GET http://localhost:8080/api/auth/health`. Hệ thống dùng PostgreSQL/Flyway, nên cần khởi động DB trước.

### Bước 3. Chạy frontend

Mở terminal khác:

```powershell
cd web
npm install
npm run dev
```

Truy cập **http://localhost:5173**. Vite proxy `/api` và `/ws` về backend `localhost:8080` theo `web/vite.config.js`.

### Bước 4. Worker — chỉ khi cần biên dịch/chạy bot

Worker cần RabbitMQ/MinIO và sandbox Linux được thiết lập chính xác. Xem [Hướng dẫn worker hiện có](worker/README.md) và [Cài đặt và vận hành](docs/07-CAI-DAT-TRIEN-KHAI.md). Không yêu cầu bật worker để chỉ xem giao diện frontend, nhưng tính năng bot phụ thuộc worker sẽ không hoạt động đầy đủ.

### Chạy tập trung trên Linux/WSL2

Sau khi chạy Docker và kiểm tra đã có `npm install` trong `web/`, có thể tham khảo `./run-all.sh`. Script cần `sudo isolate` và phụ thuộc vào cấu hình máy; **không chạy nguyên xi trên PowerShell**.

## 5. Cổng dịch vụ mặc định

| Thành phần | Địa chỉ |
|---|---|
| Frontend Vite | `http://localhost:5173` |
| Backend API | `http://localhost:8080` |
| API Actuator/Prometheus | `http://localhost:8081/actuator/prometheus` |
| PostgreSQL | `localhost:5432` |
| RabbitMQ AMQP / quản trị | `localhost:5672` / `http://localhost:15672` |
| Redis | `localhost:6379` |
| MinIO API / Console | `http://localhost:9000` / `http://localhost:9001` |
| Prometheus / Grafana | `http://localhost:9090` / `http://localhost:3000` |

Các cổng có thể được ghi đè bằng biến môi trường trong Compose.

## 6. Tài liệu đầy đủ

1. [Danh mục tài liệu](docs/README.md)
2. [Giới thiệu, bối cảnh và yêu cầu](docs/01-TONG-QUAN-YEU-CAU.md)
3. [Kiến trúc và luồng xử lý](docs/02-KIEN-TRUC-HE-THONG.md)
4. [Luật chơi, trạng thái và game engine](docs/03-LUAT-CHOI-VA-ENGINE.md)
5. [Frontend, UX và cấu trúc giao diện](docs/04-FRONTEND.md)
6. [REST API, xác thực và WebSocket](docs/05-API-VA-GIAO-THUC.md)
7. [Dữ liệu, queue và luồng bot](docs/06-DU-LIEU-VA-WORKER.md)
8. [Cài đặt, triển khai, vận hành](docs/07-CAI-DAT-TRIEN-KHAI.md)
9. [An toàn, tình huống lỗi và xử lý](docs/08-BAO-MAT-VA-XU-LY-LOI.md)
10. [Kiểm thử, giám sát và tiêu chí nghiệm thu](docs/09-KIEM-THU-DANH-GIA.md)
11. [Đối chiếu thiết kế cũ với phiên bản hiện tại](docs/10-DOI-CHIEU-THIET-KE.md)
12. [Hướng dẫn người chơi](docs/11-HUONG-DAN-CHOI.md)

Các đặc tả chuyên sâu cũ tại `ottv2/docs/` và ADR tại `docs/adr/` được **giữ nguyên** để truy vết quyết định kỹ thuật.

## 7. Kiểm thử tối thiểu

```powershell
.\mvnw.cmd test
cd web
npm run build
```

Việc tồn tại test trong mã nguồn không đồng nghĩa toàn bộ test đã pass ở mọi môi trường; hãy chạy và lưu kết quả khi nghiệm thu. Test tải được đặt tại `loadtest/`.

## 8. Quy ước đóng góp

- Thay đổi luật engine phải cập nhật đặc tả, unit test và quy tắc tương ứng ở frontend.
- Thay đổi REST/WebSocket phải kiểm tra đồng thời `api/` và `web/`.
- Không commit `node_modules/`, `target/`, `dist/`, log, file `.env` thật hoặc khóa bí mật.
- Commit theo chức năng. Với cập nhật tài liệu, tạo commit `docs: complete README and technical documentation` sau khi rà soát, rồi push lên nhánh làm việc.

---

**Phạm vi commit tài liệu:** chỉ cập nhật Markdown phục vụ hướng dẫn sử dụng và bảo trì dự án; không thay đổi báo cáo đề xuất/thiết kế Word. Các mục tiêu hoặc thiết kế chưa có mã triển khai được phân biệt với chức năng hiện có.

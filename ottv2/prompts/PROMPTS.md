# Bộ prompt cho từng giai đoạn (dán lần lượt vào Antigravity)

Quy tắc dùng: xong giai đoạn nào thì chạy test, đọc lại code, commit git, rồi mới sang giai đoạn tiếp theo.

---
## Giai đoạn 1 - Engine
```
Đọc AGENTS.md, docs/00-design-decisions.md và docs/02-engine-classes.md.
Chỉ làm GIAI ĐOẠN 1 trong docs/05-roadmap.md:
1. Tạo Maven multi-module (parent + module `engine`, Java 21, JUnit 5, jqwik).
2. Cài đặt model: Board, Cell, Position, Piece, Move, GameState, Team, PieceType, CellType.
3. Cài đặt các Strategy: MovementStrategy, CombatResolver, VisionStrategy, WinCondition (có Composite), CellEffect.
4. PieceFactory, MoveCommand/PassCommand, GamePhase (State).
5. MapGenerator đối xứng + MapValidator (BFS, kiểm tra công bằng đường đi).
6. Observer: GameEventPublisher, ReplayRecorder (xuất JSON theo format trong docs/02).
7. GameEngine (Facade) + GameEngineBuilder + MatchRunner với RandomBotController và ScriptedBotController.
8. Unit test: luật Kéo-Búa-Bao, từng CellEffect, fog of war, cùng seed ra cùng kết quả, property test "map sinh ra luôn hợp lệ".
Hãy lập Implementation Plan cho tôi duyệt trước khi viết code.
```

---
## Giai đoạn 2 - Bot runner và sandbox
```
Đọc AGENTS.md, docs/02-engine-classes.md (phần giao thức bot) và docs/04-sandbox.md.
Chỉ làm GIAI ĐOẠN 2:
1. Module `worker`: ProcessBotController giao tiếp stdin/stdout theo giao thức JSON một dòng mỗi lượt.
2. Interface Sandbox + cài đặt IsolateSandbox (gọi isolate qua ProcessBuilder). Thêm LocalProcessSandbox KHÔNG an toàn, chỉ dùng cho dev, phải có cảnh báo rõ trong code.
3. Giới hạn: thời gian/lượt, bộ nhớ, pids, tắt mạng, giới hạn output theo bảng trong docs/04.
4. Bot mẫu + thư viện template cho Java, C++, Python (người dùng chỉ viết hàm decide).
5. CLI: `run-match --botA <path> --botB <path> --seed N` in kết quả và ghi file replay.
6. Bộ bot độc hại để test sandbox (fork bomb, đọc file hệ thống, mở socket, vòng lặp vô hạn, ăn RAM, output khổng lồ).
Môi trường Linux (WSL2/Docker). Lập Implementation Plan cho tôi duyệt trước. Cuối cùng liệt kê rủi ro bảo mật còn lại.
```

---
## Giai đoạn 3a - API, DB, Auth
```
Đọc AGENTS.md, docs/01-architecture.md và docs/03-database.md.
Chỉ làm GIAI ĐOẠN 3a:
1. Module `api` (Spring Boot 3) theo kiến trúc phân tầng: controller / service / repository.
2. Flyway migration từ DDL trong docs/03 (tạo sẵn partition tháng cho matches).
3. docker-compose.yml: PostgreSQL, Redis, RabbitMQ, MinIO.
4. Auth (JWT), đăng ký/đăng nhập, quản lý bot, rate limit cơ bản.
5. Test tích hợp bằng Testcontainers.
Lập Implementation Plan cho tôi duyệt trước.
```

---
## Giai đoạn 3b - Submit, Queue, Compile
```
Đọc AGENTS.md, docs/01-architecture.md (mục luồng A, Message Queue) và docs/04-sandbox.md.
Chỉ làm GIAI ĐOẠN 3b:
1. Endpoint POST /bots/{id}/submissions: lưu source vào MinIO, ghi DB, Outbox pattern, publish `compile.q`.
2. Outbox publisher (poll bảng outbox_events chưa publish).
3. Compile worker: consume compile.q, biên dịch trong sandbox, lưu artifact theo sha256, gửi result.q.
4. Consumer cập nhật trạng thái submission, idempotent, retry với backoff, DLQ.
5. Giới hạn: kích thước source, tần suất submit, hàng đợi trạng thái cho người dùng.
Lập Implementation Plan cho tôi duyệt trước.
```

---
## Giai đoạn 3c - Match worker và Replay viewer
```
Đọc AGENTS.md, docs/01-architecture.md và docs/02-engine-classes.md.
Chỉ làm GIAI ĐOẠN 3c:
1. Match worker: consume match queue, tải artifact (cache LRU), chạy MatchRunner với 2 bot trong sandbox, upload replay.json.gz lên MinIO, gửi result.q. Worker KHÔNG kết nối trực tiếp DB.
2. API lấy danh sách trận, tải replay, lấy log bot (chỉ chủ bot được xem).
3. Frontend React + PixiJS: replay viewer (play/pause/step, vẽ bàn 9x9, quân, ô đặc biệt, sự kiện).
Lập Implementation Plan cho tôi duyệt trước.
```

---
## Giai đoạn 4 - Ladder và Elo
```
Đọc AGENTS.md, docs/01-architecture.md (luồng B) và docs/03-database.md.
Chỉ làm GIAI ĐOẠN 4:
1. MatchmakingStrategy (Strategy pattern): ghép theo Elo gần nhau, nới cửa sổ theo thời gian chờ, tránh gặp lại liên tiếp. Mỗi cặp đấu 2 ván cùng seed, đổi bên.
2. Scheduler chỉ chạy trong khung giờ của bảng ladder_windows (mặc định 19:00-22:00 thứ 2-6, múi giờ Asia/Ho_Chi_Minh).
3. Throttle số trận/bot/phút.
4. RatingCalculator (Strategy) Elo; Ranking consumer single-writer, cập nhật bot_ratings và Redis ZSET.
5. API bảng xếp hạng (đọc từ Redis), lịch sử trận.
6. Test mô phỏng với bot giả để kiểm tra Elo hội tụ hợp lý.
Lập Implementation Plan cho tôi duyệt trước.
```

---
## Giai đoạn 5 - Tournament
```
Đọc AGENTS.md, docs/01-architecture.md (luồng C) và docs/03-database.md (phần tournament).
Chỉ làm GIAI ĐOẠN 5:
1. Chốt bảng: freeze ladder, snapshot Top 32 vào tournament_entries (khóa submission_id).
2. BracketBuilder (Builder): sinh 31 bracket_nodes theo thứ tự seed chuẩn trong docs/03.
3. Mỗi node là Bo3 (2 ván đổi bên, ván phụ nếu hòa); đẩy vào match.tournament.q.
4. Observer lắng nghe MatchFinished: xác định thắng thua, đẩy người thắng lên node cha.
5. API và WebSocket để trình chiếu bracket trực tiếp.
6. Chức năng admin: tạo giải, chốt bảng, chạy lại trận lỗi hạ tầng.
Lập Implementation Plan cho tôi duyệt trước.
```

---
## Giai đoạn 6 - Load test
```
Đọc AGENTS.md và docs/01-architecture.md (mục Ước lượng tải).
Chỉ làm GIAI ĐOẠN 6:
1. Script k6 hoặc Gatling mô phỏng 3.000 người dùng: đăng nhập, submit code, xem bảng xếp hạng.
2. Bot giả lập cho ladder để tạo tải trận đấu.
3. Dashboard Prometheus/Grafana: queue depth, queue lag, thời gian chạy trận, CPU worker.
4. Cấu hình autoscale worker theo queue depth (KEDA hoặc script).
5. Báo cáo kết quả: nút thắt cổ chai và đề xuất chỉnh số worker.
Lập Implementation Plan cho tôi duyệt trước.
```

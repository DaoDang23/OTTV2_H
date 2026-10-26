# 06 — Thiết kế dữ liệu, hàng đợi và bot worker

## 1. Lưu trữ

| Hệ thống | Công dụng | Vùng code |
|---|---|---|
| PostgreSQL 16 | Dữ liệu người dùng, bot, submission, phòng, giải đấu, kết quả, lịch sử | `api/src/main/resources/db/migration` |
| Flyway | Quản lý tiến hóa schema có kiểm soát | `V1__...sql` đến `V20__...sql` trong ZIP |
| RabbitMQ | Compile job, match job, result event | `api/`, `worker/` |
| Redis | Online presence, cache/rating/lobby tùy triển khai | `api/ladder/` |
| MinIO | Source bot, artifact, replay, log dung lượng lớn | `api/storage/`, `worker/` |

Không lưu secret MinIO/PostgreSQL vào frontend. File Compose chứa mật khẩu mẫu local; production phải thay và không mở public các cổng quản trị.

## 2. Một số nhóm đối tượng

- **User/Profile:** định danh, thông tin hiển thị, thống kê thắng/thua, liên kết match history.
- **Bot/Submission:** chủ sở hữu, ngôn ngữ, phiên bản mã nguồn, trạng thái build, artifact ID.
- **Room/Match:** code phòng, danh sách thành viên, trạng thái turn, game result và authoritative participant.
- **Replay:** đường dẫn object, sự kiện/winner và metadata đủ tái hiện trận.
- **Ladder/Tournament:** mùa, rating, ứng viên, seed, bracket, status, standings.

Schema thực tế chịu quyết định cuối cùng từ Flyway. Không dựa vào cấu trúc bảng suy đoán từ tên DTO.

## 3. Migration và tương thích

Migration trong ZIP bắt đầu từ khởi tạo schema, lần lượt mở rộng submission, match, ladder, tournament, profile, room persistence và integrity. Ví dụ:

- `V1__initial_schema.sql`: cấu trúc ban đầu.
- `V6__user_profile_and_stats.sql`: thông tin profile/stat.
- `V11__matchmaking_queue_and_ready_status.sql`: queue/ready.
- `V17__store_user_match_replays.sql`: replay user.
- `V18__persist_authoritative_rooms.sql`: lưu trạng thái phòng.
- `V19__track_recorded_ranked_room_results.sql`: theo dõi kết quả ranked.
- `V20__pvp_match_authoritative_participants.sql`: dữ liệu participant authoritative.

Không sửa migration đã chạy ở môi trường có dữ liệu; thêm `V21__...sql` nếu cần cập nhật schema.

## 4. Chu trình submission/bot

1. Xác thực chủ bot và giới hạn số byte/giới hạn tần suất gửi mã nguồn.
2. Tạo bản ghi submission và lưu source vào MinIO.
3. Enqueue tác vụ compile (RabbitMQ) và worker thực hiện build.
4. Lưu artifact theo version bất biến và cập nhật compile status.
5. Khi ghép trận: gửi match job, worker tải artifact theo object key.
6. `isolate` khởi tạo hộp sandbox riêng cho từng bot, giới hạn CPU/RAM/process/time.
7. Bot trả JSON một dòng cho mỗi yêu cầu lượt, ví dụ `{"pieceId":3,"to":[5,2]}`.
8. Engine xác thực và xử lý, ghi replay/log; worker gửi kết quả để API cập nhật DB/rating.

## 5. Cô lập mã nguồn không tin cậy

`--sandbox local` trong worker chỉ phục vụ phát triển; **không chạy mã người dùng không tin cậy trên host**. Chế độ `isolate` cần Linux/WSL2, cgroup, cấu hình network isolation, quyền hệ thống, filesystem read-only và thư mục làm việc giới hạn. Chỉ cài isolate không tự động đảm bảo mọi chính sách an toàn; cần kiểm thử bằng các mẫu `worker/bots/malicious` trong môi trường dành cho bảo mật.

Các giới hạn mặc định được mô tả trong `worker/README.md` (thời gian trả lời bot, RAM cgroup, process limit), có thể thay đổi theo runner/config. Phải kiểm tra phiên bản worker trước khi công bố số liệu.

## 6. Queue, retry và idempotency

- Publish job có ID duy nhất và đường dẫn artifact immutable.
- Worker có thể nhận lại job khi restart ⇒ xử lý lại an toàn, tránh double rewards.
- Nếu compile/match hỏng, phát event lỗi có lý do và correlation ID; UI hiển thị trạng thái fail/retry thích hợp.
- DB commit thất bại sau khi publish event cần cơ chế nhất quán (outbox hoặc retry có idempotency).
- MinIO object bị mất hoặc checksum sai phải kết thúc job với lỗi xác định.

## 7. Lịch sử và replay

Tối thiểu replay lưu được seed/config, người tham gia, đội hình ban đầu, các command/sự kiện theo thứ tự và kết quả cuối. Người xem phải kiểm tra quyền truy cập; object keys cần được server ký/cấp quyền phù hợp, không công khai bucket có mã nguồn bot riêng tư.

## 8. Theo dõi chỉ số

Đề xuất đo queue backlog, thông lượng job, thời gian compile, thời gian match, sandbox failures, tỉ lệ retry, thời gian truy xuất MinIO, tỉ lệ replay không tải được và kết quả trả lại API. Dashboard có trong `monitoring/`, nguồn chỉ số riêng worker ở `/metrics` theo `worker/README.md`.

# 09 — Kế hoạch kiểm thử, đo hiệu năng và nghiệm thu

## 1. Mục tiêu

Kiểm tra rằng luật bàn 9×9, API, đồng bộ PvP, bot worker và dữ liệu kết quả nhất quán; chỉ công bố thông số tải sau khi thực hiện đo có điều kiện cụ thể.

## 2. Chiến lược kiểm thử

| Cấp | Tập trung | Công cụ/vị trí |
|---|---|---|
| Unit | `canBeat`, move, winner, clock, combat, condition | `engine/src/test`, kiểm thử JS đề xuất |
| Integration | Auth/room/match/DB migration | `api/src/test`, test container khi có |
| Contract | API JSON/STOMP schema | API + frontend utils |
| E2E | Đăng nhập → phòng → ván → kết quả/replay | trình duyệt + API/DB |
| Security | AuthZ, CSRF, replay permissions, bot isolation | kiểm thử âm/worker malicious samples |
| Load | HTTP/room, RPS, độ trễ, queue, worker | `loadtest/k6/ottv2.js`, Prometheus |

## 3. Ma trận kiểm thử gameplay bắt buộc

| ID | Tình huống | Kỳ vọng |
|---|---|---|
| GP-01 | Khởi tạo bàn | 9×9; 9 quân Xanh + 9 quân Đỏ; ô xanh I1, ô đỏ A9 |
| GP-02 | Di chuyển 1 ô trực giao | Được nếu ô trống và không vướng luật |
| GP-03 | Di chuyển chéo/2 ô | Từ chối |
| GP-04 | Người A điều khiển quân B | Từ chối |
| GP-05 | Búa ăn Kéo | Búa sống, Kéo bị loại |
| GP-06 | Kéo ăn Bao | Kéo sống, Bao bị loại |
| GP-07 | Bao ăn Búa | Bao sống, Búa bị loại |
| GP-08 | Hai quân cùng loại | Không được ăn theo luật UI |
| GP-09 | Quân đi vào ô đồng đội | Không cho phép |
| GP-10 | Xanh vào I1 | Xanh thắng ngay |
| GP-11 | Đỏ vào A9 | Đỏ thắng ngay |
| GP-12 | Ăn quân đối thủ cuối cùng | Phe còn quân thắng ngay |
| GP-13 | Client/engine xử lý quân tấn công yếu hơn | Kỳ vọng phải thống nhất; hiện cần đối chiếu |
| GP-14 | Xoay bàn (`isFlipped`) | Luật và tọa độ không đổi |

## 4. Kiểm thử luồng PvP

1. Hai tài khoản đăng nhập độc lập.
2. A tạo phòng; B tham gia; C bị từ chối khi phòng đủ nếu mode quy định 2 người.
3. A/B xác nhận phe, lượt, đồng hồ.
4. A đi hợp lệ; B nhận cập nhật cùng tọa độ, trạng thái captured/turn.
5. Thử gửi move trái lượt và gửi lại move trùng.
6. Thử B mất mạng, refresh/reconnect rồi đồng bộ snapshot.
7. Kết thúc bằng một trong hai điều kiện thắng; xác minh UI, backend và lịch sử/replay khớp.

## 5. Kiểm thử bot/worker

- Ngôn ngữ mẫu Java/Python/C++ được biên dịch/chạy theo cấu hình.
- Bot không trả lời đúng deadline, trả JSON sai, vượt CPU/memory/process, cố truy cập file/network phải được xử lý an toàn.
- Kiểm tra replay file, log object key, result event, error/retry.
- Worker có thể cùng xử lý nhiều tác vụ mà không dùng trùng isolate box ID.

## 6. Đo hiệu năng

### 6.1 Những biến cần ghi

- Máy chủ: CPU, RAM, ổ đĩa, OS/JVM, container memory.
- Mạng: LAN/Internet/Tunnel, độ trễ cơ sở, bandwidth.
- Kịch bản: người dùng đồng thời, tỉ lệ đọc/ghi, phòng hoạt động, tần suất move, độ dài trận.
- Metrics: request/s, error %, p50/p95/p99 latency, WS disconnect/reconnect, room update propagation, queue backlog, worker time.

### 6.2 Phương pháp

1. Chạy smoke test 2–10 người và xác nhận tính đúng.
2. Tăng tải theo các mốc 50, 100, 250, 500, 1.000 người (chỉ khi máy đủ năng lực).
3. Giữ mỗi mốc trong thời gian xác định, ghi lỗi và tài nguyên.
4. Kiểm tra tính nhất quán số trận/room sau tải; so sánh retry/match result.
5. Làm lại với tunnel nếu muốn đánh giá mạng Internet.

Các mốc ở đây là **kịch bản đề xuất**. Không ghi “hệ thống chịu 1.000 concurrent” nếu không có báo cáo đo thực nghiệm, biểu đồ và bối cảnh đủ tái lập.

## 7. Mẫu báo cáo kết quả

| Trường | Giá trị ghi sau khi chạy |
|---|---|
| Ngày/commit/branch | ... |
| Thiết bị/JDK/Node/Docker | ... |
| Số người dùng đồng thời | ... |
| RPS và tỷ lệ lỗi | ... |
| p50/p95/p99 API | ... |
| WS disconnect và độ trễ sync | ... |
| RAM, CPU, queue backlog | ... |
| Số trận hoàn thành, kết quả trùng | ... |
| Kết luận/mức đáp ứng | ... |

## 8. Lệnh kiểm thử

```powershell
.\mvnw.cmd test
cd web
npm run build
```

Đối với k6, đọc `loadtest/README.md` và file `loadtest/k6/ottv2.js`; tuyệt đối không chạy stress test vào hạ tầng của bên khác khi chưa được phép.

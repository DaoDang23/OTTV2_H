# 10 — Đối chiếu thiết kế OTT ban đầu và mã nguồn OTTv2 hiện tại

## 1. Mục đích

Hai tài liệu đầu vào gồm báo cáo đề xuất trò Oẳn Tù Tì thời gian thực quy mô 1.000 người và bản thiết kế Client–Server v2 có nguyên mẫu TCP nhiều Room/luật. Code hiện nay là nền tảng đấu **Búa–Bao–Kéo trên bàn cờ 9×9**, kết hợp React, Spring Boot, Java engine và bot worker. Tài liệu mới giữ định hướng kiến trúc/khả năng chịu tải, đồng thời **không trình bày nguyên mẫu cũ như chức năng game hiện tại**.

## 2. Những điểm đã thay đổi

| Khía cạnh | Thiết kế trước | Phần code hiện tại |
|---|---|---|
| Gameplay | Một vòng chọn ROCK/PAPER/SCISSORS; nhiều round | Bàn 9×9, 18 quân, di chuyển theo lượt và khắc chế khi ăn |
| Đạt chiến thắng | Điểm round/best of | Xanh vào I1, Đỏ vào A9 hoặc ăn hết quân đối phương; còn chế độ giới hạn lượt |
| Giao thức | TCP text line `HELLO`, `CREATE_ROOM`, `CHOICE` | HTTP REST + WebSocket/STOMP |
| Client | TCP client minh họa | React/Vite UI với bàn tương tác |
| Server | Room manager/protocol handler thiết kế | Spring Boot API, room services, security |
| Phân xử luật | GameRule theo mỗi room trong thiết kế | Java engine và `web/src/utils/gameRules.js` |
| Dữ liệu | Room/Match có thể trong memory; V2 thêm DB | PostgreSQL/Flyway, Redis, MinIO |
| Thi đấu bot | Chưa phải luồng cốt lõi của TCP prototype | Worker compile/match + Linux isolate |
| Hạ tầng | Laptop 8GB/Cloudflare Tunnel mục tiêu | Docker Compose, giám sát, k8s manifest, loadtest |

## 3. Phần giá trị nên giữ từ bản đầu

- **Mô hình server authoritative** và kiểm tra trạng thái trước khi xử lý request.
- Phân rã hệ thống thành client, server, quản lý phòng và bộ luật có thể thay thế.
- Bài toán reconnect, timeout, room capacity, dữ liệu trùng, định dạng thông điệp và mã lỗi.
- Tư duy phát triển từ bản tối thiểu sang phiên bản mở rộng, có mục tiêu kiểm chứng.
- Mục tiêu triển khai tiết kiệm tài nguyên, sử dụng tunnel khi không thể port-forward qua CGNAT.
- Bảng tình huống lỗi và kịch bản kiểm thử tải để đánh giá khách quan.

## 4. Những câu KHÔNG nên dùng cho báo cáo code hiện tại

- “Mỗi người chọn ROCK/PAPER/SCISSORS bí mật rồi server mở đồng thời” nếu dùng để mô tả một lượt bàn cờ 9×9.
- “Một quân đi được tám hướng như vua” nếu mô tả gameplay UI hiện tại; `getValidMoves` dùng bốn hướng.
- “Dùng TCP line-delimited `CHOICE|ROCK`” cho frontend React hiện tại.
- “Đã chịu 1.000 người cùng lúc” khi chưa có benchmark có thể kiểm chứng.
- “Worker sandbox local an toàn” vì chế độ local chạy mã bot trên host.

## 5. Điểm cần thống nhất thêm trong mã nguồn

Frontend `getValidMoves` không cho chủ động đánh vào quân có lợi thế trước mình; Java engine `OrthogonalOneStep` + `RpsCombatResolver` lại có thể xử lý trường hợp quân phòng thủ thắng. Cần thống nhất rule contract theo mode và thêm unit/integration tests. Điều này không thay đổi quy tắc cốt lõi do chủ dự án xác nhận: chiếm ô đích đúng màu hoặc ăn hết quân đối phương là thắng.

## 6. Nguồn dẫn nội bộ

- Hai file DOCX gốc do nhóm lập dự án cung cấp.
- `web/src/utils/gameRules.js`, `web/src/components/InteractiveBoard.jsx`.
- `engine/.../movement/OrthogonalOneStep.java`, `rules/ReachTargetCondition.java`, `rules/AnnihilationCondition.java`.
- `api/`, `worker/`, `compose.yaml`, `monitoring/`, `loadtest/`.

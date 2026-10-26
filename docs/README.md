# Bộ tài liệu kỹ thuật OTTv2

Bộ tài liệu Markdown dành cho mã nguồn **OTTV2_H**: giới thiệu game bàn cờ 9×9, kiến trúc, thành phần triển khai, giao thức, cài đặt, kiểm thử và hướng dẫn sử dụng. Nội dung được đối chiếu với mã nguồn và giữ riêng các vấn đề chưa được kiểm chứng. **Bộ này không chỉnh sửa tài liệu đề xuất/báo cáo Word của nhóm.**

## Danh mục

| File | Nội dung chính | Người đọc |
|---|---|---|
| `01-TONG-QUAN-YEU-CAU.md` | Bối cảnh, mục tiêu, nhóm FR/NFR, phạm vi | Giảng viên, nhóm phát triển |
| `02-KIEN-TRUC-HE-THONG.md` | Module, phân tầng, luồng request/job/replay | Lập trình backend, kiến trúc sư |
| `03-LUAT-CHOI-VA-ENGINE.md` | Luật 9×9, trạng thái, combat, tính quyết định | Người viết engine/bot |
| `04-FRONTEND.md` | Giao diện, màn hình, trạng thái và cấu trúc React | Frontend/QA |
| `05-API-VA-GIAO-THUC.md` | REST, session/token/CSRF, STOMP, mã lỗi | Frontend/backend |
| `06-DU-LIEU-VA-WORKER.md` | PostgreSQL, Flyway, Redis, queue, MinIO, worker | Backend/DevOps |
| `07-CAI-DAT-TRIEN-KHAI.md` | Môi trường, local, Docker, WSL2, tunnel, troubleshooting | Người triển khai |
| `08-BAO-MAT-VA-XU-LY-LOI.md` | Phân tích tình huống Client–Server và biện pháp | QA/bảo mật |
| `09-KIEM-THU-DANH-GIA.md` | Kế hoạch kiểm thử, đo tải, nghiệm thu | QA/nghiệm thu |
| `10-DOI-CHIEU-THIET-KE.md` | Những chỗ tài liệu thiết kế cũ khác code hiện tại | Người bảo trì |
| `11-HUONG-DAN-CHOI.md` | Luật chơi đơn giản, đích đúng màu, chiến thuật | Người chơi/demo |

## Nguyên tắc sử dụng

1. Khi cần kiểm tra cách chạy thật, đối chiếu **code và cấu hình** trước: `pom.xml`, `compose.yaml`, `api/src/main/resources/application.properties`, `web/vite.config.js`.
2. Khi cần tham khảo quyết định đã chốt, đọc `docs/adr/` và `ottv2/docs/00-design-decisions.md`.
3. Khi cần hiểu lịch sử đề tài, tham khảo riêng hai bản đề xuất và thiết kế Word trước đây; không đồng nhất nguyên mẫu chọn Búa–Bao–Kéo với game chiến thuật 9×9 hiện tại.
4. Mục tiêu về hiệu năng, an toàn, đồng thời phải được xem là **chỉ tiêu nghiệm thu** nếu chưa có số đo.
5. Mọi thay đổi API, schema hoặc luật chơi kéo theo chỉnh sửa các file tương ứng và bổ sung test.

## Tình trạng và giới hạn

Tài liệu biên soạn từ ZIP dự án được cung cấp, **không chạy dịch vụ live hoặc đo benchmark trên môi trường của người dùng**. Các biến môi trường, dependency ngoài và kết quả triển khai có thể khác nhau. Bản nguồn chứa một số định hướng phát triển dài hạn; không mặc định rằng mọi tính năng đã được triển khai hoặc sẵn sàng cho môi trường production.

## Các nguồn trong dự án được giữ nguyên

- `api/README.md`, `worker/README.md`, `k8s/README.md`, `loadtest/README.md`.
- `ottv2/docs/00-design-decisions.md` đến `06-deferred-features.md`.
- `docs/adr/0001-record-architecture-decisions.md`.
- `docs/agents/`: hướng dẫn thuật ngữ và quy trình làm việc, không phải tài liệu vận hành hệ thống.

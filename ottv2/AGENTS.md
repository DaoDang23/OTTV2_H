# OTTV2 - Quy tắc cho agent

Dự án OTTV2: nền tảng thi đấu bot (Bot Coding Battle) dạng cờ Kéo-Búa-Bao, bàn 9x9, hỗ trợ 2.000-3.000 CCU.

## Quy tắc bắt buộc
- Luôn đọc `docs/` trước khi viết code. Tài liệu trong `docs/` là nguồn chân lý.
- Stack: Java 21, Spring Boot 3, Maven multi-module (`engine`, `api`, `worker`), PostgreSQL 16, Redis 7, RabbitMQ, MinIO.
- Module `engine` phải thuần domain: KHÔNG phụ thuộc DB, MQ, Spring hay bất kỳ framework nào.
- Mọi ngẫu nhiên phải đi qua một `seed`; cùng seed + cùng danh sách nước đi phải ra cùng kết quả.
- Áp dụng Design Pattern như mô tả trong `docs/02-engine-classes.md` (Strategy, Factory, Observer, Command, Builder, Template Method, Adapter, Facade, State, Composite).
- Kiến trúc phân tầng: Presentation -> Business Logic -> Data Access. Không gọi ngược tầng.
- Viết unit test cho mọi class mới. Dùng property-based test cho map generator.
- Mỗi lần chỉ làm đúng giai đoạn được giao trong `prompts/`.
- Trước khi code: lập Implementation Plan và chờ tôi duyệt.
- Nếu cần thay đổi thiết kế hoặc luật game, hỏi tôi trước, không tự đổi.
- Code sandbox/bảo mật: giải thích rõ lựa chọn và liệt kê rủi ro còn lại.

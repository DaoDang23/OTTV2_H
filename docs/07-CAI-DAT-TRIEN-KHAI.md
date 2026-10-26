# 07 — Hướng dẫn cài đặt, chạy local và triển khai

## 1. Môi trường

- Windows 10/11 PowerShell/VS Code hoặc Linux; worker isolate cần **Linux/WSL2**.
- JDK **21** theo cấu hình Maven, Maven Wrapper `mvnw.cmd`/`mvnw`.
- Node.js tương thích Vite 6, npm, Docker Engine + Docker Compose.
- Git, dung lượng lưu trữ đủ để tải dependency Maven/npm và image Docker.

## 2. Kiểm tra công cụ

```powershell
java -version
node -v
npm -v
docker --version
docker compose version
```

Sử dụng JDK 21; nếu JAVA_HOME trỏ đến JDK khác, build có thể báo target release lỗi.

## 3. Chuẩn bị thư mục dự án và cấu hình

- Mở đúng thư mục repo root (chứa `pom.xml`, `compose.yaml`, `web/`, `api/`).
- Kiểm tra `api/src/main/resources/application.properties`; các biến môi trường có giá trị mặc định cho local như `DATABASE_URL`, `RABBITMQ_HOST`, `MINIO_ENDPOINT`.
- `web/.env.example` là tài liệu cấu hình mẫu; không commit `.env` chứa secret.
- Cổng backend thường `8080`, frontend `5173`; nếu xung đột thì giải quyết từ cấu hình thay vì hard-code trong nhiều file.

## 4. Chạy hạ tầng bằng Docker

```powershell
docker compose up -d postgres rabbitmq redis minio
docker compose ps
```

**Quan trọng:** tên service ở câu lệnh trên khớp cấu hình Compose đã cung cấp; nếu file Compose thay đổi, dùng `docker compose config --services` để kiểm tra. Các tài khoản/mật khẩu mẫu chỉ dùng môi trường phát triển.

## 5. Chạy backend

```powershell
.\mvnw.cmd -pl api -am spring-boot:run
```

Sau khi API khởi động, kiểm tra:

```powershell
Invoke-RestMethod http://localhost:8080/api/auth/health
```

Nếu lỗi kết nối DB, kiểm tra container PostgreSQL, URL và biến `DATABASE_*`; migration Flyway chạy tự động khi datasource khả dụng.

## 6. Chạy frontend

Mở terminal VS Code khác ở repo root:

```powershell
cd web
npm install
npm run dev
```

Mở `http://localhost:5173`. Vite proxy `/api` và `/ws` đến backend local. Chỉ frontend chạy được không đồng nghĩa room, bot và lịch sử hoạt động khi backend chưa chạy.

## 7. Build và kiểm thử

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl api -am package
cd web
npm run build
```

`dist/`, `target/`, `node_modules/` không đưa vào Git. Với worker, xem `worker/README.md` để build jar và cấu hình isolate.

## 8. Chạy bot worker

- Chuẩn bị Linux/WSL2, cgroup và công cụ isolate đúng quyền.
- Chạy RabbitMQ/MinIO và kiểm tra endpoint.
- Build `worker` theo hướng dẫn gốc, đặt worker command theo hệ điều hành.
- Với bot không tin cậy, **không dùng `--sandbox local`**.
- Worker có thể cần box ID riêng cho từng tiến trình; tránh trùng khi chạy song song.

## 9. Lỗi thường gặp

| Hiện tượng | Nguyên nhân khả dĩ | Cách kiểm tra |
|---|---|---|
| `npm`/`node` not recognized | Chưa cài hoặc PATH sai | `node -v`, mở terminal mới |
| Vite 503 `BACKEND_OFFLINE` | Spring Boot chưa chạy | Backend log, `localhost:8080` |
| API không kết nối DB | Container tắt/sai mật khẩu | `docker compose ps`, datasource vars |
| Flyway validate failed | Schema lệch migration | Kiểm tra lịch sử Flyway, không xóa DB production |
| WebSocket không kết nối | Proxy/origin/endpoint | `vite.config.js`, `WebSocketConfig` |
| 401/403 khi tạo phòng | Session/cookie/CSRF | DevTools Network, server security |
| Bot fail ngay | Artifact sai, thiếu isolate, vi phạm giới hạn | Worker log/sandbox stderr |
| `git push` lỗi | Remote, auth, branch, quyền repo | `git remote -v`, `git branch --show-current` |

## 10. Triển khai Internet và hạ tầng tối thiểu

Tài liệu đề xuất ban đầu chọn máy cá nhân Core i5-12500H, RAM 8 GB và Cloudflare Tunnel để tránh mở cổng/địa chỉ IP tĩnh. Đó là **phương án đề xuất**, không phải kết quả kiểm chứng hiện tại. Nếu dùng tunnel hoặc proxy, cần xem xét HTTPS, WebSocket upgrade, `X-Forwarded-*`, cookie Secure, rate limit và các cổng quản trị nội bộ. Không công khai RabbitMQ UI/MinIO Console/PostgreSQL trực tiếp.

## 11. Quy trình phát hành

1. Kiểm tra commit, lint/build/test, kiểm tra secret và migration.
2. Sao lưu DB/object storage; chạy migration có quản lý.
3. Khởi động dependency và health-check từng tầng.
4. Deploy API/worker trước, cập nhật frontend tương thích API.
5. Test luồng đăng nhập, tạo phòng, 1 trận, kết quả và replay trên môi trường đích.
6. Theo dõi metric sau phát hành; chuẩn bị rollback tương thích schema.

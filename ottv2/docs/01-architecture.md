# 01 - Kiến trúc hệ thống tổng thể

## Sơ đồ

```
                         ┌─────────────────────────┐
   Sinh viên (Browser) ─►│  Nginx / CDN (static)   │
   React + PixiJS        └───────────┬─────────────┘
   (Replay viewer)                   │ HTTPS / WSS
                                     ▼
┌───────────────────────────────────────────────────────────────────────┐
│ PRESENTATION LAYER                                                    │
│  REST Controllers (auth, submit, ranking, tournament)                 │
│  WebSocket Hub (live spectate, ladder status, notifications)          │
│  Rate limiter · DTO mapping · Validation                              │
├───────────────────────────────────────────────────────────────────────┤
│ BUSINESS LOGIC LAYER  (Modular Monolith, mỗi module là 1 bounded ctx) │
│  Identity&Auth | Submission | Matchmaking Scheduler | Tournament      │
│  Ranking/Elo   | Match Orchestrator | GAME ENGINE (thư viện thuần)    │
├───────────────────────────────────────────────────────────────────────┤
│ DATA ACCESS LAYER                                                     │
│  Repository (JPA/jOOQ) · Redis client · Blob client · Outbox publisher│
└──────┬──────────────────┬──────────────────┬──────────────────┬───────┘
       ▼                  ▼                  ▼                  ▼
  PostgreSQL           Redis              RabbitMQ          MinIO / S3
 (primary+replica)  (ladder ZSET,      (compile/match/     (source, binary,
                     cache, lock)        result queues)      replay.gz)
                                            │
              ┌─────────────────────────────┴───────────────┐
              ▼                                             ▼
   COMPILE WORKERS (sandbox)              MATCH WORKERS (pool)
   pull compile.q                         pull match.q
                                          Referee (trusted) = Game Engine
                                          ├─ pipe ─ Bot A (sandbox)
                                          └─ pipe ─ Bot B (sandbox)

 Observability: Prometheus + Grafana + Loki; KEDA/HPA autoscale theo độ dài queue
```

## Nguyên tắc kiến trúc
1. **Game Engine là thư viện thuần (pure domain).** Không phụ thuộc DB, MQ, framework. Dùng chung ở worker, unit test, và replay viewer.
2. **Modular monolith cho API, tách riêng worker.** API cluster 2-3 node đủ cho 3.000 CCU. Phần nặng (compile, chạy bot) nằm ở worker, scale ngang độc lập.
3. **Worker không giữ credential DB.** Worker chỉ nhận job từ queue, lấy artifact từ blob storage, trả kết quả qua `result.q`.
4. **Compile một lần, chạy nhiều lần.** Binary lưu theo `sha256` của source.

## Luồng nghiệp vụ

**A. Submit code**
```
User -> POST /bots/{id}/submissions -> lưu source vào MinIO, ghi DB (status=PENDING)
     -> Outbox -> compile.q -> Compile Worker (sandbox)
     -> result.q -> status=COMPILED | COMPILE_ERROR (kèm log)
     -> (nếu OK) chạy 1-3 trận smoke test với bot mẫu -> status=ACTIVE
```

**B. Ladder (vòng bảng)**
```
Scheduler (mỗi 5s, chỉ chạy trong khung giờ cấu hình ở ladder_windows)
  -> lấy bot ONLINE từ Redis ZSET (key = elo)
  -> ghép cặp theo Elo gần nhau, cửa sổ Elo nới dần theo thời gian chờ, tránh gặp lại liên tiếp
  -> tạo Match(status=QUEUED) + seed -> match.ladder.q
Match Worker -> chạy trận -> upload replay -> result.q
Ranking Consumer (single-writer) -> cập nhật Elo -> rating history -> cập nhật ZSET
```

**C. Chung kết**
```
Admin/Cron -> freeze ladder -> snapshot Top 32 -> sinh bracket
-> mỗi trận Bo3 (2 ván đổi bên + ván phụ nếu hòa) -> match.tournament.q (ưu tiên cao nhất)
-> khi 2 node con xong, node cha tự sẵn sàng (Observer lắng nghe MatchFinished)
```

## Ước lượng tải
Giả sử 3.000 bot online, mỗi bot đấu tối đa 1 trận / 5 phút:
- ~5 trận/giây. Mỗi trận = 2 ván, mỗi ván ~3-8 giây thực tế.
- ~100 tiến trình bot đồng thời.
- Worker node 16 vCPU chạy ~8 trận đồng thời -> cần ~6-10 node cho ladder, +2 node cho compile và chung kết.
- Burst submit gần deadline: 3.000 submit trong vài phút, compile 5-15s/bài -> 4-6 compile worker, queue hấp thụ phần dư.
- Khi đông quá: throttle số trận/bot/phút thay vì cố chạy hết.

## Message Queue (RabbitMQ)

| Queue | Producer | Consumer | Ưu tiên |
|---|---|---|---|
| `compile.q` | Submission | Compile worker | Cao |
| `match.tournament.q` | Tournament | Match worker (pool riêng) | Cao nhất |
| `match.ladder.q` | Matchmaking | Match worker | Thấp |
| `result.q` | Workers | Ranking, Match service | Cao |
| `*.dlq` | (retry thất bại) | Admin xử lý | |

Bắt buộc: consumer **idempotent** theo `match_id`; retry với backoff (tối đa 3 lần); phân biệt lỗi hạ tầng (retry) với lỗi do bot (xử thua, không retry); **Outbox pattern** để ghi DB và publish MQ không lệch nhau.

## Stack

| Thành phần | Công nghệ |
|---|---|
| Backend + Engine | Java 21, Spring Boot 3 (Maven multi-module: engine, api, worker) |
| Frontend | React + PixiJS/Canvas, WebSocket |
| DB | PostgreSQL 16 (+ read replica), Flyway |
| Cache/ranking | Redis 7 (ZSET, lock, rate limit) |
| MQ | RabbitMQ |
| Blob | MinIO / S3 |
| Triển khai | Docker Compose (dev), Kubernetes + KEDA (prod) |
| Giám sát | Prometheus, Grafana, Loki |

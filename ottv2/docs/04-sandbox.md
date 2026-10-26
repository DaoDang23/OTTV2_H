# 04 - Sandboxing cho chấm bot

## So sánh

| Công nghệ | Cô lập | Overhead | Phù hợp OTTV2 |
|---|---|---|---|
| Docker thường | Trung bình (chia sẻ kernel) | 200-500ms | Chỉ làm lớp đóng gói, không đủ chặn code lạ |
| Docker + seccomp + cgroups v2 + rootless | Khá | Như trên | Chấp nhận nếu siết cấu hình |
| **isolate** (IOI, CMS, DMOJ) | Tốt (namespace + cgroup + syscall) | Rất thấp (~ms) | **Rất hợp**, đo CPU/memory chính xác |
| **nsjail** (Google) | Tốt, cấu hình bằng file | Rất thấp | **Hợp**, linh hoạt |
| **gVisor (runsc)** | Rất tốt | Syscall chậm 10-30% | Tốt khi cần bảo mật mạnh trên K8s |
| Firecracker microVM | Rất tốt (VM thật) | ~125ms, tốn RAM | Hơi nặng cho đồ án sinh viên |

## Khuyến nghị
**isolate (hoặc nsjail) chạy bên trong container worker, kết hợp seccomp và cgroups v2.**
1. Mỗi trận khởi động 2 process bot: cần overhead mili-giây. Container-per-bot quá chậm ở 5 trận/giây.
2. Cần đo chính xác CPU time và peak memory để lưu vào `match_participants`.
3. Referee (code tin cậy) tạo pipe tới từng bot bị cô lập: bot không nói chuyện được với nhau hay với mạng.

Nâng cấp bảo mật: worker chạy runtime gVisor, hoặc trên VM riêng, không chung node với API và DB.

## Giới hạn tài nguyên

| Tham số | Compile | Chạy bot |
|---|---|---|
| Thời gian | 30s CPU | 1s/lượt (hoặc time bank ~10s/ván) |
| Wall time | 60s | ~3x CPU limit |
| Bộ nhớ | 1 GB | 256 MB |
| Process/thread | 64 | 8-16 (`pids.max`) |
| Mạng | Tắt | Tắt hoàn toàn |
| Filesystem | Chỉ ghi thư mục work | Read-only + tmpfs nhỏ |
| Output | 10 MB log | 64 KB/lượt, 4 MB stderr tổng |
| Syscall | seccomp allowlist | seccomp allowlist chặt (không ptrace, mount, socket...) |

Theo ngôn ngữ:
- Java: khởi động chậm, nhân hệ số thời gian lượt đầu hoặc tính giờ từ khi nhận `READY`. Dùng `-Xmx`, `-XX:+UseSerialGC`, `-Xshare:auto`.
- Python: công bố rõ limit hoặc nhân hệ số thời gian.
- C++: compile `-O2`.

## Quy trình

Compile Worker:
1. Tải source từ MinIO vào thư mục tạm.
2. `isolate --box-id=N` (không mạng, giới hạn tài nguyên) chạy javac / g++ / py_compile.
3. Ghi artifact lên MinIO (key = sha256 source).
4. Trả kết quả + log (cắt 64KB) qua `result.q`.

Match Worker:
1. Cache artifact cục bộ (LRU theo sha256).
2. Tạo 2 sandbox (box-id riêng, cpuset riêng).
3. Referee mở pipe stdin/stdout từng bot, chạy MatchRunner.
4. Mỗi lượt: gửi JSON, đọc 1 dòng trong thời hạn, validate.
5. Thu CPU time / peak mem / exit status, dọn sandbox trong `finally`.
6. Upload replay, gửi `result.q`.

## Công bằng và an toàn
- Pin CPU core (`cpuset`) cho mỗi sandbox.
- Referee không tin bot: đọc tối đa N byte một dòng, parse JSON an toàn, lỗi định dạng coi là lỗi của bot.
- Bot không có đường ghi vào replay hay DB; kết quả luôn do referee tạo.
- Worker chỉ ra được MQ và MinIO (egress allowlist), không chạm DB.
- Giới hạn submit (1 lần/30s, tối đa N lần/ngày), source tối đa 256 KB, quét tương đồng code (MOSS/JPlag) trước chung kết.
- Bộ test "bot độc hại" trong CI: fork bomb, đọc /etc/passwd, mở socket, vòng lặp vô hạn, ăn RAM, output khổng lồ. Tất cả phải bị chặn.
- isolate/nsjail chỉ chạy trên Linux. Trên Windows/macOS dùng WSL2 hoặc Docker.

# 05 - Lộ trình triển khai

1. **Giai đoạn 1 - Engine:** model, luật, map generator đối xứng, replay, unit test property-based (map luôn hợp lệ, cùng seed ra cùng kết quả).
2. **Giai đoạn 2 - Bot runner:** giao thức stdin/stdout, sandbox isolate, bot mẫu 3 ngôn ngữ, chạy 1 trận bằng CLI.
3. **Giai đoạn 3 - Platform:** (3a) API + DB + auth, (3b) submit + queue + compile worker, (3c) match worker + lưu replay + viewer.
4. **Giai đoạn 4 - Ladder + Elo:** matchmaking, khung giờ, bảng xếp hạng Redis.
5. **Giai đoạn 5 - Tournament:** snapshot Top 32, bracket, Bo3, trình chiếu trực tiếp.
6. **Giai đoạn 6 - Load test:** bot giả lập 3.000 tài khoản (k6/Gatling), đo queue lag, chỉnh số worker.

Mỗi giai đoạn chỉ được sang bước tiếp theo khi test pass và đã được review.

# 00 - Quyết định thiết kế (luật game đã chốt)

| Vấn đề | Quyết định |
|---|---|
| Lượt đi | Luân phiên (A rồi B), không đồng thời. Tránh xử lý xung đột nước đi. |
| Công bằng | Map đối xứng tâm (point symmetry): sinh nửa bản đồ rồi lật. Mỗi cặp đấu chơi 2 ván cùng seed, đổi bên. |
| Hai quân cùng hệ gặp nhau | Không được đi vào ô đó (nước đi không hợp lệ). |
| Hợp lệ map | Sau khi sinh, kiểm tra bằng BFS: mỗi bên đều tới được Target Cell và độ dài đường đi ngắn nhất của hai bên bằng nhau. |
| Fog of war | (Hoãn - giai đoạn sau) Bot chỉ nhận ô và quân trong bán kính nhìn (Chebyshev <= 2) quanh quân của mình. Terrain tĩnh biết trước, bẫy thì ẩn. |
| Ô biến dị (MUTATION) | (Hoãn - giai đoạn sau) Quân đi vào đổi hệ theo vòng Kéo -> Búa -> Bao -> Kéo. |
| Ô tăng tốc (SPEED) | (Hoãn - giai đoạn sau) Quân được đi thêm 1 bước theo cùng hướng (nếu ô tiếp theo hợp lệ). |
| Ô bẫy (TRAP) | (Hoãn - giai đoạn sau) Mặc định: mất 1 lượt. Cấu hình được (chết / lùi về spawn). |
| Kết thúc | Chạm đích, hết quân đối phương, hoặc hết N lượt (mặc định 200). Hết lượt xét điểm: số quân còn lại, rồi khoảng cách tới đích. |
| Bot thua | Quá thời gian, vượt bộ nhớ, crash hoặc nước đi sai liên tiếp thì xử thua (hoặc bỏ lượt, tùy cấu hình). |
| Determinism | Toàn bộ ngẫu nhiên đi qua `seed`. Replay chỉ cần seed + danh sách nước đi. |
| Quân cờ | Kéo ăn Bao, Bao ăn Búa, Búa ăn Kéo. |
| Bàn cờ | Lưới 9x9. Cấu trúc map (chướng ngại, bẫy, tăng tốc, biến dị) thay đổi theo seed mỗi trận. |

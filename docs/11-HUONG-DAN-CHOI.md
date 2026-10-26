# 11 — Hướng dẫn người chơi: Oẳn Tù Tì chiến thuật 9×9

## 1. Mục tiêu của trò chơi

Bạn điều khiển phe **Xanh** hoặc **Đỏ** trên bàn cờ 9×9. Mỗi phe có 9 quân mang biểu tượng **Búa (👊), Bao (✋), Kéo (✌️)**. Không cần ăn hết quân để thắng: nếu đưa một quân đi tới **ô đích đúng màu của mình**, bạn thắng ngay. Ngược lại, ăn hết quân đối phương cũng giúp thắng ngay.

## 2. Hai ô đích

- **Ô xanh I1:** nằm ở **góc trên bên phải**; quân **Xanh** đi vào ô này ⇒ **Xanh thắng**.
- **Ô đỏ A9:** nằm ở **góc dưới bên trái**; quân **Đỏ** đi vào ô này ⇒ **Đỏ thắng**.

Nhớ quy tắc: **Xanh → xanh, Đỏ → đỏ**. Chỉ cần *một* quân phe mình đến đích, bất kể đó là Búa, Bao hay Kéo.

## 3. Di chuyển

1. Đến lượt mình, nhấn vào quân thuộc màu của bạn.
2. Các nước đi hợp lệ sẽ được đánh dấu.
3. Nhấn ô bạn muốn chuyển đến.
4. Mỗi lần chỉ đi **một ô lên, xuống, sang trái hoặc sang phải**.
5. Không đi chéo, nhảy quân, ra ngoài bàn hay đi vào ô đã có quân phe mình.

## 4. Cách ăn quân

| Bạn dùng | Ăn được |
|---|---|
| 👊 Búa | ✌️ Kéo |
| ✌️ Kéo | ✋ Bao |
| ✋ Bao | 👊 Búa |

Ví dụ: Búa Xanh đứng cạnh Kéo Đỏ ⇒ nếu đến lượt Xanh, có thể đưa Búa vào đúng ô Kéo để ăn quân Kéo. Nếu ô đó là Bao Đỏ, Búa Xanh không được chủ động ăn trong cách chơi UI hiện tại.

## 5. Điều kiện thắng

**Thắng cách 1 — Đến đích:** Xanh vào I1 hoặc Đỏ vào A9.

**Thắng cách 2 — Ăn hết quân:** đối phương không còn quân nào trên bàn.

Các chế độ có thể thêm đồng hồ/giới hạn lượt, được hiển thị riêng khi bắt đầu trận.

## 6. Mẹo chiến thuật

- Luôn quan sát đường tiếp cận ô đích của cả hai phe; một quân lọt qua phòng tuyến có thể kết thúc ván.
- Duy trì cả ba loại quân để tạo thế khắc chế lẫn nhau.
- Không dùng Búa đẩy thẳng vào Bao: chọn đường tránh hoặc dùng Kéo khống chế Bao.
- Kiểm tra dấu hiệu ô đi hợp lệ trước khi nhấn; đừng nhầm ô đích của đối thủ.

## 7. Giải đáp nhanh

**Một quân có thể thắng bằng tới đích không?** Có, bất kỳ loại quân nào thuộc đúng phe.

**Đi chéo có được không?** Không, giao diện hiện tại chỉ cho 4 hướng.

**Búa gặp Búa có ăn được không?** Không, quân cùng loại không ăn nhau.

**Quân Đỏ đi tới ô xanh có thắng không?** Không. Chỉ quân Đỏ tới ô đỏ A9 mới thắng bằng mục tiêu; tương tự Xanh phải đến I1.

**Làm thế nào biết ai đến lượt?** Quan sát chỉ báo lượt và quân có thể chọn trên giao diện.

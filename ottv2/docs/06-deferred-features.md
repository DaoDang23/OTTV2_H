# 06 - Tính năng hoãn (Deferred Features)

Các tính năng sau đã được quyết định hoãn lại ở Giai đoạn 1 nhằm thu hẹp phạm vi, tập trung làm cho Core Engine ổn định trước.

## 1. Ô biến dị (MutationEffect)
- **Thiết kế ban đầu:** Khi quân bước vào ô biến dị (MUTATION), sẽ đổi hệ theo vòng Kéo -> Búa -> Bao -> Kéo.
- **Cách cắm vào Engine sau này:** 
  - Đã có sẵn interface `CellEffect`.
  - Chỉ cần tạo class `MutationEffect implements CellEffect`.
  - Cập nhật `MapGenerator` để sinh ô `MUTATION` và `ReplayRecorder` bắt event `PieceMutatedEvent`.

## 2. Ô tăng tốc (SpeedEffect)
- **Thiết kế ban đầu:** Cho phép quân đi thêm 1 bước theo cùng hướng nếu ô tiếp theo hợp lệ.
- **Cách cắm vào Engine sau này:** 
  - Tạo `SpeedEffect implements CellEffect`.
  - Có thể tạo thêm `OrthogonalWithSlide implements MovementStrategy` để tính toán cả nước trượt, và cập nhật `MoveCommand` hoặc thêm rule vào Engine để xử lý logic đi liên tiếp.

## 3. Ô bẫy (TrapEffect)
- **Thiết kế ban đầu:** Quân dính bẫy (TRAP) mất 1 lượt (hoặc chết / lùi về spawn tuỳ cấu hình). Bẫy bị ẩn trên map.
- **Cách cắm vào Engine sau này:**
  - Tạo `TrapEffect implements CellEffect`.
  - Bổ sung trường `hidden` trong `Cell` để `VisionStrategy` không tiết lộ cho đối phương.

## 4. Sương mù chiến tranh (RadiusFogVision)
- **Thiết kế ban đầu:** Bot chỉ nhận thông tin các ô và quân trong bán kính Chebyshev <= 2 xung quanh quân mình.
- **Cách cắm vào Engine sau này:**
  - Đã có sẵn interface `VisionStrategy`.
  - Engine mặc định sẽ chạy bằng `FullVision`.
  - Khi triển khai sương mù, chỉ cần tạo `RadiusFogVision(radius)` và truyền vào lúc build `GameEngine` qua `GameEngineBuilder.withVisionStrategy()`.

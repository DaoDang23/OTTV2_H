# 04 — Thiết kế frontend và trải nghiệm người chơi

## 1. Công nghệ và tổ chức

Frontend là SPA React 19 dùng Vite 6. Mã nguồn tại `web/src/`; `main.jsx` khởi tạo ứng dụng, `App.jsx` điều phối màn hình, component và trạng thái. Giao tiếp backend thông qua `utils/api.js`, `utils/roomApi.js` và STOMP/SockJS tùy chế độ. CSS được đặt tại `web/src/style.css`; ứng dụng có cấu hình Tailwind.

## 2. Các màn hình/thành phần

| Thành phần | Mục đích | Tác động dữ liệu |
|---|---|---|
| `Layout.jsx`/`Sidebar.jsx` | Khung trang, điều hướng | Trạng thái UI |
| `GameModes.jsx` | Lựa chọn chế độ chơi | Điều hướng vào local/PvP/bot |
| `LocalGameModal.jsx` | Chơi trên cùng thiết bị | Luật cục bộ `gameRules.js` |
| `RoomGameModal.jsx` | Chơi phòng trực tuyến | REST/WS, state phòng |
| `InteractiveBoard.jsx` | Bàn 9×9 tương tác, quân và ô đích | Props `pieces`, `currentTurn`, `validMoves` |
| `Profile.jsx` | Thông tin người chơi | Profile APIs |
| `Leaderboard.jsx` | Bảng xếp hạng | Ladder/rating APIs |
| `BattleCode.jsx` | Quản lý thi đấu và mã bot | Bot/submission APIs |
| `Tournaments.jsx`, `TournamentDetailModal.jsx` | Giải đấu | Tournament APIs |
| `ReplayModal.jsx` | Xem lại trận đấu | Replay data |
| `ErrorBoundary.jsx` | Ngăn lỗi render lan toàn ứng dụng | Bắt lỗi component |

## 3. Trải nghiệm bàn cờ

Bàn mặc định hiển thị 9 cột `A–I` và 9 hàng `1–9`. Quân **Xanh (A)** ở phía dưới, quân **Đỏ (B)** phía trên. Quân thể hiện loại bằng emoji 👊 ✋ ✌️; ô **xanh I1** ở góc trên phải là đích Xanh, ô **đỏ A9** ở góc dưới trái là đích Đỏ. Người chơi chọn quân đang đến lượt, UI đánh dấu các ô hợp lệ bằng dấu tròn hoặc highlight ăn quân. Quân được lựa chọn có viền nổi; ô vừa đi được đánh dấu để dễ theo dõi.

### 3.1 Các trạng thái UI

1. **Chưa chọn quân:** bàn hiển thị các quân, lượt đang diễn ra, đồng hồ nếu mode bật.
2. **Đã chọn quân:** hiển thị các đích hợp lệ, có thể hủy bằng cách bấm ô khác không hợp lệ.
3. **Chọn ô trống:** chuyển quân một ô trực giao nếu hợp lệ.
4. **Ăn quân:** quân khắc chế tiến vào ô quân địch, phát hiệu ứng âm thanh.
5. **Kết thúc:** hiển thị người thắng và lý do `TARGET_REACHED` hoặc `ANNIHILATION`.
6. **Mất kết nối:** khóa tương tác cần server và cho phép đồng bộ lại theo chính sách phòng.

### 3.2 Quy tắc thao tác

- `playerSide='BOTH'`: local game có thể chọn phe đang đến lượt.
- `playerSide='A'/'B'`: người dùng chỉ được điều khiển phe tương ứng.
- `isFlipped`: xoay góc nhìn nhưng không làm thay đổi tọa độ thật và các ô đích.
- Mọi thay đổi game online chỉ nên hiện thành kết quả chính thức sau khi server xác nhận; client có thể vẽ hiệu ứng dự đoán nhưng phải sẵn sàng rollback/resync.

## 4. Kiến trúc state phía client

- State UI tạm: modal mở, chọn quân, highlight, animation.
- State trò chơi: quân còn sống, loại quân, lượt hiện tại, last move, winner, clock.
- State mạng: room code, người tham gia, websocket connection, loading/error, snapshot server.
- State người dùng: thông tin đăng nhập, hồ sơ, rating và các yêu cầu API.

Không gộp `pvpElo` với rating bot. Không lấy thông tin `winner` do một client tự gửi làm nguồn xác thực nếu backend chưa chốt.

## 5. Giao tiếp với backend

Vite proxy `/api` đến `http://localhost:8080` và `/ws` đến `ws://localhost:8080`. Với production, thay proxy bằng URL cùng origin hoặc cấu hình gateway thích hợp. Khi API offline, proxy Vite có thể trả `503` và message `BACKEND_OFFLINE`; UI nên hiển thị thông báo thay vì xóa state người dùng.

## 6. Yêu cầu giao diện và khả dụng

- Responsive cho desktop, laptop và màn hình nhỏ, giữ bàn vuông không méo quân.
- Màu Đỏ/Xanh cần đi kèm text hoặc icon để người khó phân biệt màu vẫn xác định phe.
- Đánh dấu ô đích rõ `I1`/`A9` kể cả khi ô đang có quân.
- Không cho phép double submit ở lúc xử lý move hoặc thanh toán trạng thái phòng.
- Có trạng thái loading, offline, empty state và error message rõ ràng.
- Cân nhắc bàn phím/ARIA label cho ô bàn cờ; hiện component chủ yếu dùng click.

## 7. Kiểm thử frontend

- Render đúng 81 ô; vị trí hai đích và 18 quân ban đầu.
- Chọn quân sai phe không thay đổi selected piece.
- Nước đi 4 hướng; chiếu 8 hướng phải bị bác.
- Tất cả cặp loại thắng/thua/hòa.
- `isFlipped` không làm đổi tọa độ logic.
- Âm thanh, highlights, replay không cản việc gửi move.
- Thông báo thắng theo ô đích/tuyệt diệt; UI hiển thị trạng thái ván sau refresh/reconnect.

## 8. Khởi động và đóng gói

```powershell
cd web
npm install
npm run dev
```

Build production:

```powershell
npm run build
```

Thư mục sinh `web/dist/` không commit vào repo; frontend cần backend và hạ tầng để dùng đủ chức năng phòng, tài khoản và bot.

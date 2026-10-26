# 03 — Luật chơi, bàn cờ và cơ chế game engine

> **Bản luật được người dùng xác nhận theo ảnh bàn cờ:** hai phe **Xanh** và **Đỏ** sử dụng quân Búa–Bao–Kéo; **Xanh đến ô xanh, Đỏ đến ô đỏ là thắng**; **ăn hết quân đối thủ cũng thắng**. Tài liệu ưu tiên quy tắc này cho chế độ bàn cờ 9×9 hiện tại.

## 1. Tổng quan màn đấu

| Thuộc tính | Giá trị mặc định |
|---|---|
| Số phe | 2: Xanh (`Team A`) và Đỏ (`Team B`) |
| Kích thước bàn | 9 hàng × 9 cột = 81 ô |
| Quân ban đầu | Mỗi phe 9 quân: 3 Búa, 3 Bao, 3 Kéo |
| Mã quân | `ROCK`, `PAPER`, `SCISSORS` |
| Vị trí ban đầu | Đỏ ở phía trên, Xanh ở phía dưới; đội hình ba tuyến |
| Lượt | Hai bên thay phiên di chuyển một quân hợp lệ |
| Di chuyển trên giao diện hiện tại | 1 ô **lên/xuống/trái/phải**; không đi chéo |
| Đích Xanh | **I1**, `(row=0,col=8)`: ô **xanh** ở góc trên bên phải |
| Đích Đỏ | **A9**, `(row=8,col=0)`: ô **đỏ** ở góc dưới bên trái |

### 1.1 Cách đọc tọa độ

- Tên cột từ **A** đến **I**, đánh số hàng từ **1** đến **9**.
- Trong code: `row`/`col` là chỉ số bắt đầu từ 0. Ví dụ `I1 = (0,8)`, `A9 = (8,0)`.
- Khi xoay bàn cờ để xem từ phía người chơi khác, **tọa độ logic không đổi**; chỉ thay đổi cách hiển thị.

```text
      A   B   C   D   E   F   G   H   I
  1   ·   ·   S   ·   P   ·   R   ·  [X]
  2   ·   ·   ·   R   S   P   ·   ·   ·
  3   ·   ·   P   ·   R   ·   S   ·   ·
  4   ·   ·   ·   ·   ·   ·   ·   ·   ·
  5   ·   ·   ·   ·   ·   ·   ·   ·   ·
  6   ·   ·   ·   ·   ·   ·   ·   ·   ·
  7   ·   ·   S   ·   R   ·   P   ·   ·
  8   ·   ·   ·   P   S   R   ·   ·   ·
  9  [Đ]  ·   R   ·   P   ·   S   ·   ·
```

**Chú thích sơ đồ:** `R` = Búa (`ROCK`), `P` = Bao (`PAPER`), `S` = Kéo (`SCISSORS`). Ba hàng trên là quân **Đỏ**, ba hàng dưới là quân **Xanh**. `[X]` là **ô đích Xanh I1**, `[Đ]` là **ô đích Đỏ A9**. Sơ đồ mô phỏng đúng cấu hình `INITIAL_PIECES` trong `web/src/utils/gameRules.js`, không phải ảnh chụp giao diện.

## 2. Luật di chuyển

1. Người chơi chỉ chọn quân thuộc phe mình khi đến lượt.
2. Trong một lượt, chọn **một quân còn sống**, sau đó di chuyển tối đa **một ô trực giao**.
3. Không được đi ra ngoài biên 9×9, không được đi vào ô có quân đồng đội và không được đứng chung với quân khác.
4. Nếu ô đích nước đi có quân đối phương, chỉ được **ăn quân** nếu loại của quân di chuyển khắc chế loại quân ở ô đó (quy tắc giao diện PvP/local hiện tại).
5. Quân cùng loại của hai phe không thể ăn nhau. Không cho phép nhảy qua quân khác hoặc di chuyển chéo.
6. Sau nước đi hợp lệ, lượt chuyển sang phe kia, trừ khi trận kết thúc.

### 2.1 Ví dụ

- Quân Xanh loại **Búa** ở `E7`, đối phương loại **Kéo** ở `E6` ⇒ có thể đi `E7 → E6`, ăn quân Kéo.
- Quân Xanh loại **Búa** ở `E7`, đối phương loại **Bao** ở `E6` ⇒ giao diện hiện tại **không cho chọn ô E6** để tấn công.
- Nếu `E6` có quân cùng đội hoặc quân cùng loại của đối phương ⇒ không được đi vào.
- `E7 → F6` là nước chéo nên không hợp lệ dù ô trống.

## 3. Luật ăn quân Búa–Bao–Kéo

| Quân tấn công | Quân phòng thủ | Kết quả |
|---|---|---|
| Búa (`ROCK`) | Kéo (`SCISSORS`) | Búa ăn Kéo |
| Kéo (`SCISSORS`) | Bao (`PAPER`) | Kéo ăn Bao |
| Bao (`PAPER`) | Búa (`ROCK`) | Bao ăn Búa |
| Hai quân cùng loại | Cùng loại | Không ăn được, ô bị chặn |
| Quân thuộc cùng phe | Bất kỳ | Không ăn quân mình |
| Quân yếu hơn quân địch | Quân khắc chế nó | Không cho phép chủ động đi vào ô đó theo quy tắc UI |

Khi ăn thành công: quân bị ăn biến mất khỏi bàn, quân tấn công chuyển vào ô đích, bộ đếm kills của quân có thể tăng; nếu đó là quân cuối cùng của đối thủ, ván đấu kết thúc ngay.

## 4. Điều kiện chiến thắng — quan trọng nhất

### 4.1 Chiếm đúng ô đích của phe mình

- **Phe Xanh thắng** ngay khi *bất kỳ quân Xanh còn sống* đi tới **I1 — ô màu xanh**.
- **Phe Đỏ thắng** ngay khi *bất kỳ quân Đỏ còn sống* đi tới **A9 — ô màu đỏ**.
- Không yêu cầu phải đưa Búa, Bao hay Kéo cụ thể vào đích: **loại quân không ảnh hưởng điều kiện tới đích**.
- Quân chỉ thắng khi đứng trên **ô đích tương ứng phe mình**, không phải chỉ vì tới một góc bất kỳ.

### 4.2 Ăn hết quân đối phương

- Nếu số quân Đỏ còn sống bằng **0**, trong khi phe Xanh còn quân ⇒ **Xanh thắng**.
- Nếu số quân Xanh còn sống bằng **0**, trong khi phe Đỏ còn quân ⇒ **Đỏ thắng**.
- Hàm kiểm tra frontend hiện có nhánh hiếm `DRAW/ALL_CAPTURED` nếu cả hai phe không còn quân.

### 4.3 Kết thúc bởi giới hạn hoặc đồng hồ

Mã nguồn có cấu hình giới hạn lượt ở các chế độ: mặc định 200, tournament 300, sandbox 500, quick test 100; giao diện có đồng hồ Fischer `3+2`, `5+3`, `1+1`. Kết quả khi timeout/đạt giới hạn phụ thuộc chế độ/luồng giải quyết cụ thể, không thay thế **hai điều kiện thắng cốt lõi** ở trên.

## 5. Trình tự một lượt

```text
Đến lượt Xanh/Đỏ
 → chọn quân đúng phe
 → UI tính ô hợp lệ và đánh dấu trên bàn
 → người chơi chọn ô hợp lệ
 → kiểm tra quân địch và quan hệ khắc chế
 → cập nhật tọa độ, trạng thái quân và event
 → kiểm tra ĐÃ TỚI Ô ĐÍCH hoặc ĐỐI THỦ HẾT QUÂN
      ├─ Có → GAME_OVER + công bố bên thắng
      └─ Không → đổi lượt, đồng bộ trạng thái
```

## 6. Mô hình dữ liệu engine

- `Board`: các `Cell` kèm occupant, cell type/effect.
- `Piece`: id, `Team`, `PieceType`, vị trí, `alive`, chiến lược di chuyển.
- `GameState`: toàn bộ trạng thái (phase, turn, các quân, bàn).
- `MoveCommand`: xác minh lượt, tính hợp lệ của đích, thay đổi board và phát `GameEvent`.
- `RpsCombatResolver`: phân xử ai thắng khi hai quân gặp nhau.
- `WinCondition`: `ReachTargetCondition`, `AnnihilationCondition`, `TurnLimitCondition`, có thể tổ hợp bằng `CompositeWinCondition`.
- `ReplayRecorder`: thu lại chuỗi event, phục vụ tái hiện/điều tra kết quả.

## 7. Điểm khác nhau giữa frontend và Java engine cần đồng bộ

**Cần chú ý khi nghiệm thu:** `web/src/utils/gameRules.js` chỉ đưa ô có quân địch vào `getValidMoves` khi **quân tấn công thắng** theo Búa–Bao–Kéo. Trong khi đó, `engine/movement/OrthogonalOneStep.java` cho phép di chuyển vào ô quân địch **khác loại** (kể cả loại khắc chế quân tấn công), và `RpsCombatResolver` khi ấy có thể xử **quân phòng thủ thắng**. Đây là khác biệt thực tế trong source, không nên che đi bằng cách diễn giải mọi mode đều giống hệt nhau.

**Quyết định tài liệu cho gameplay được bạn xác nhận:** quy tắc giao diện 9×9 là tiêu chuẩn trình bày; trước khi coi backend/worker và giao diện dùng một luật duy nhất, cần thống nhất hành vi di chuyển tấn công thua và bổ sung test tích hợp.

Ngoài ra Java engine có `TRAP`, `SPEED`, `MUTATION`, `OBSTACLE`, bản đồ sinh từ seed; các cơ chế này chỉ áp dụng ở chế độ có cấu hình tương ứng. Không coi chúng là vật cản mặc định trên bàn PvP trong ảnh.

## 8. Bộ test chấp nhận luật

| Mã | Thiết lập | Kỳ vọng |
|---|---|---|
| G-01 | Quân Xanh đứng cạnh ô trống theo hướng ngang | Được đi đúng một ô |
| G-02 | Chọn ô chéo | Không có trong danh sách hợp lệ |
| G-03 | Ô kế bên có quân cùng đội | Không được đi vào |
| G-04 | Búa tấn công Kéo | Ăn quân Kéo, Búa sống |
| G-05 | Kéo tấn công Bao | Ăn quân Bao, Kéo sống |
| G-06 | Bao tấn công Búa | Ăn quân Búa, Bao sống |
| G-07 | Búa gặp Búa khác phe | Không cho vào ô theo UI |
| G-08 | Búa tấn công Bao | Không được chọn nước đó theo UI; test đối chiếu Java engine |
| G-09 | Quân Xanh hợp lệ vào `I1` | Xanh thắng `TARGET_REACHED` |
| G-10 | Quân Đỏ hợp lệ vào `A9` | Đỏ thắng `TARGET_REACHED` |
| G-11 | Phe Xanh ăn quân Đỏ cuối cùng | Xanh thắng `ANNIHILATION` |
| G-12 | Phe Đỏ ăn quân Xanh cuối cùng | Đỏ thắng `ANNIHILATION` |
| G-13 | Hai bên cùng xem phòng, một người gửi nước đi | Trạng thái xác nhận thống nhất |
| G-14 | Client gửi nước đi trái lượt | Server từ chối |

## 9. Tham chiếu triển khai

- `web/src/utils/gameRules.js`: `INITIAL_PIECES`, `TARGET_A`, `TARGET_B`, `canBeat`, `getValidMoves`, `checkWinner`.
- `web/src/components/InteractiveBoard.jsx`: ô màu, đánh dấu lượt, các quân và UI bàn cờ.
- `engine/.../model/PieceType.java`, `movement/OrthogonalOneStep.java`, `rules/ReachTargetCondition.java`, `rules/AnnihilationCondition.java`.
- `engine/.../command/MoveCommand.java`, `combat/RpsCombatResolver.java`.

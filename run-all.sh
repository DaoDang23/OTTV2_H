#!/bin/bash

echo "=== 1. Khởi tạo hộp cát Isolate ==="
sudo isolate --cg --init
sudo isolate --cg -b 200 --init
sudo isolate --cg -b 201 --init

# Tự động tắt toàn bộ tiến trình con khi bấm Ctrl + C
trap 'echo "Đang tắt toàn bộ hệ thống..."; sudo kill $(jobs -p) 2>/dev/null; exit' SIGINT SIGTERM

echo "=== 1.5. Cập nhật và cài đặt Engine ==="
./mvnw -pl engine install -DskipTests

echo "=== 2. Khởi động API Backend (Spring Boot) ==="
./mvnw -pl api spring-boot:run > api.log 2>&1 &

echo -n "⏳ Đang chờ Backend khởi động (khoảng 10-15s)..."
# Vòng lặp chờ: Cứ 1 giây lại ngó vào api.log xem có chữ "Started" chưa
while ! grep -q "Started " api.log; do
  sleep 1
  echo -n "."
done
echo -e "\n✅ Backend đã mở cửa!"

echo "=== 3. Khởi động Compile Worker ==="
sudo ./mvnw -pl worker exec:java -Dexec.mainClass="ottv2.worker.compile.CompileWorkerCommand" > compile_worker.log 2>&1 &

echo "=== 4. Khởi động Match Worker ==="
sudo ./mvnw -pl worker exec:java -Dexec.mainClass="ottv2.worker.match.MatchWorkerCommand" > match_worker.log 2>&1 &

echo "=== 5. Khởi động Giao diện Web (Vite) ==="
(cd web && npm run dev -- --host) &

echo "===================================================="
echo "✅ HỆ THỐNG ĐÃ CHẠY TẤT CẢ TRONG 1 TERMINAL!"
echo "👉 Truy cập Web tại: http://localhost:5173"
echo "👉 Xem log lỗi (nếu cần): tail -f api.log match_worker.log"
echo "👉 Bấm [Ctrl + C] để tắt toàn bộ cùng lúc."
echo "===================================================="

wait

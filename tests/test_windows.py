"""Kiểm tra trên Windows: ping (đọc thời gian/TTL), cơ chế tự thay file khi cập nhật."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import QuetCamera as Q

ok, ms, ttl = Q.ping_detail("127.0.0.1", 1000)
print("ping 127.0.0.1:", ok, ms, ttl)
assert ok and ttl, "ping không đọc được TTL"
off = Q.ping_detail("192.0.2.123", 500)   # địa chỉ dành cho tài liệu, không tồn tại
print("ping 192.0.2.123:", off)
assert not off[0]

# Giả lập cập nhật: tiến trình đang chạy -> thoát -> file được thay -> chạy lại
open("target.bin", "w").write("old")
open("new.bin", "w").write("new")
p = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(4)"])
Q.schedule_replace(os.path.abspath("new.bin"), os.path.abspath("target.bin"), p.pid,
                   restart_cmd='cmd /c "echo restarted> restarted.txt"')
time.sleep(2)
assert open("target.bin").read() == "old", "thay file khi chương trình chưa thoát!"
for _ in range(20):
    time.sleep(1)
    if os.path.exists("restarted.txt"):
        break
assert open("target.bin").read() == "new", "không thay được file"
assert os.path.exists("restarted.txt"), "không mở lại chương trình"
print("Tự cập nhật: OK")

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
log = os.path.join(__import__("tempfile").gettempdir(), "quetcamera_update.log")
print("--- nhật ký cập nhật ---"); print(open(log, encoding="mbcs", errors="replace").read() if os.path.exists(log) else "(không có)")
assert open("target.bin").read() == "new", "không thay được file"
assert os.path.exists("restarted.txt"), "không mở lại chương trình"
print("Tự cập nhật: OK")

# Mã hoá mật khẩu bằng DPAPI và lưu thiết lập (giao diện exe sẽ đọc lại ở bước sau)
enc = Q.protect("admin:Abc@123\nroot pass")
assert enc.startswith("dpapi:"), enc[:10]
assert Q.unprotect(enc) == "admin:Abc@123\nroot pass"
Q.save_settings({"cam_range": "127.0.0.1-5", "cam_creds": enc, "ping_loop": True, "tab": 1,
                 "cam_history": ["127.0.0.1-5", "192.168.1.1-254"]})
assert Q.load_settings()["cam_range"] == "127.0.0.1-5"
print("DPAPI + lưu thiết lập: OK ->", Q.settings_path())

# Ping liên tục 3 vòng thật
m = Q.PingMonitor()
for _ in range(3):
    rows, off, on = m.round(["127.0.0.1", "192.0.2.123"], 500)
r = {x["IP"]: x for x in rows}
print(r["127.0.0.1"]["Gửi/Nhận"], r["127.0.0.1"]["TB (ms)"], r["192.0.2.123"]["Mất (%)"])
assert r["127.0.0.1"]["Gửi/Nhận"] == "3/3" and r["192.0.2.123"]["Mất (%)"] == 100
print("Ping liên tục: OK")

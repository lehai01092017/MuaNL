# -*- coding: utf-8 -*-
"""
QUÉT CAMERA – lấy IP, MAC, hãng, model, firmware, serial của camera IP qua chuẩn ONVIF
            + tab Ping dải IP + tự cập nhật phiên bản.
Tác giả: Hailm – hai.le@tnnsi.vn

Chạy:   nhấp đúp QuetCamera.exe   (hoặc: python QuetCamera.py)
Dòng lệnh (không giao diện):
   python QuetCamera.py --range 192.168.1.1-254 --cred admin:123456 --cred admin:Abc@123 --out ketqua.xlsx
   python QuetCamera.py --ping 192.168.1.1-254 --out ping.xlsx
Dải IP: 192.168.1.1-254 | 192.168.1.10-192.168.2.50 | 192.168.1.0/24 | 192.168.1.25 (cách nhau dấu phẩy)
Chỉ dùng thư viện có sẵn của Python 3.
"""
import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import os
import platform
import queue
import re
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import webbrowser
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from xml.sax.saxutils import escape

APP_NAME = "Quét camera"
APP_VERSION = "dev"   # bản build trên GitHub tự thay bằng số phiên bản, vd "1.12"
APP_AUTHOR = "Hailm"
APP_EMAIL = "hai.le@tnnsi.vn"
UPDATE_URL = os.environ.get(
    "QUETCAMERA_UPDATE_URL",
    "https://raw.githubusercontent.com/lehai01092017/MuaNL/quet-camera-exe/version.json")

DEFAULT_PORTS = "80,8000,8080,8899,2020"
CAM_COLUMNS = ["STT", "IP", "MAC", "Hãng", "Model", "Firmware", "Serial", "Hardware ID",
               "Cổng ONVIF", "Tài khoản", "Trạng thái"]
PING_COLUMNS = ["STT", "IP", "Trạng thái", "Phản hồi (ms)", "TB (ms)", "Mất (%)", "Gửi/Nhận", "TTL", "MAC",
                "Tên máy", "Đổi trạng thái"]
NUMERIC_COLS = {"STT", "Cổng ONVIF", "Phản hồi (ms)", "TB (ms)", "Mất (%)", "TTL"}
# Biểu tượng quả cầu 16x16 (PNG) trước mỗi IP
GLOBE_PNG = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAACq0lEQVR4XqWTO2hUQRSG/5m59+7du5vdZLOJ2UfQ+ExERESiQQsLRS1ECxPT2ghaKDZiJ3YiiNhEsLH20dhZW4tEiBgTEk1Mso/cvftIsnfvY2ZkVg2xEdEDUwzzfwfmP/8BttaoZFuv+buLKXX+pCGbj3clxT0icOV9T186fs2MaGchgl1SSlBmzLle+KZorz/Bs8Orm1rgZ4Of8Labk5dT6dSj3t5UplCuIvC9dn8BDbm+FGq1eqFSrtwqPT70/BdD8EIyjBGeuz11KZ5MvzSpD7fphtGoRpMxg6xWW4hZmqw1fBGLRTVXGNio26PLDw68UiwBJMne+ZRiYNOE0u5jQ11iabXJsmkL6U4T7z/bODLYg7eTRezOxfmH+XXKOa+EMhxcuT/kUIDIVktc37enP31+JMOHBrpYsxUiEdPR8kIEgUQsqkEIgYFckl08keFKqxjFUlx9p0cNdmGoPyr7+2Kk6YbwA45MtwVKVX+gO2EgojNUGx5yvVGyL2tKxShW69B5IqLT7bazRmrVkHqBRBhKLBTX4TQ8eAHH7NIa/FCg5DQBSOqFlChGsVqHclkITC/UAe5DSAKfC3z8Um1D6huTsxW4HsdiaQOligvJ9DajWLoSsEbAsXD8UF6OndopTg/nYEUYzo3kcXR/D5JxA5dO7mh7oswcPzMgRg7mpWIUS/H0SOD6/PXMSosU7Ka0TA2GzlByXAgh1ZDgrPltX1LJCIq2K+dKHtlo8deK1ZTCND9NTM8s3piZY93De5PcMjVWWw/Q02lC1wnUVJSh88t1PvV1gwnObcuiEzVI8ldB6koYsuy4Iha3NJcbaDj2aPHhjyBRlUIVS5WsWrk47ocoZPI5JaTfbI+0OCFLjqS9mawWcFKol4vjbVjFf4zw/16m3+sf1vk78d+KOQtvMmYAAAAASUVORK5CYII="


# ---------------------------------------------------------------- dải IP, tài khoản
def parse_ranges(text):
    """Trả về danh sách IP (chuỗi) theo thứ tự, không trùng."""
    out, seen = [], set()

    def add(ip):
        s = str(ip)
        if s not in seen:
            seen.add(s)
            out.append(s)

    for part in re.split(r"[,;\s]+", text.strip()):
        if not part:
            continue
        if "/" in part:
            net = ipaddress.ip_network(part, strict=False)
            for ip in (list(net.hosts()) or [net.network_address]):
                add(ip)
        elif "-" in part:
            a, b = part.split("-", 1)
            start = ipaddress.ip_address(a.strip())
            b = b.strip()
            end = ipaddress.ip_address(b) if "." in b else ipaddress.ip_address(".".join(a.strip().split(".")[:3] + [b]))
            if int(end) < int(start):
                start, end = end, start
            if int(end) - int(start) > 65535:
                raise ValueError("Dải IP quá lớn (tối đa 65.536 địa chỉ): " + part)
            for n in range(int(start), int(end) + 1):
                add(ipaddress.ip_address(n))
        else:
            add(ipaddress.ip_address(part))
    if not out:
        raise ValueError("Chưa nhập dải IP")
    return out


def split_cred(line):
    """'admin:123' | 'admin 123' | 'admin/123' -> ('admin', '123').
    Tách ở ký tự phân cách ĐẦU TIÊN, nên mật khẩu chứa ':', ' ', '/' vẫn giữ nguyên."""
    line = line.strip()
    m = re.search(r"[:/\s]", line)
    if not m:
        return line, ""
    user, rest = line[:m.start()], line[m.start():].lstrip()
    if rest[:1] in (":", "/"):          # "admin : 123" -> bỏ dấu phân cách và khoảng trắng quanh nó
        rest = rest[1:].lstrip()
    return user, rest


def parse_creds(text):
    """Mỗi dòng một tài khoản: user:mật khẩu, user mật khẩu hoặc user/mật khẩu."""
    creds = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        creds.append(split_cred(line))
    return creds


def cred_label(creds, idx):
    """Tên hiển thị của tài khoản thứ idx: 'admin' hoặc 'admin (mật khẩu #2)' nếu trùng user."""
    user = creds[idx][0] or "(không user)"
    same = [i for i, c in enumerate(creds) if c[0] == creds[idx][0]]
    return user if len(same) == 1 else f"{user} (mật khẩu #{same.index(idx) + 1})"


def ip_key(ip):
    try:
        return int(ipaddress.ip_address(ip))
    except ValueError:
        return 0


# ---------------------------------------------------------------- mạng
IS_WIN = platform.system() == "Windows"
NO_WINDOW = 0x08000000 if IS_WIN else 0  # không bật cửa sổ đen khi gọi ping/arp


def ping_detail(ip, timeout_ms=1000):
    """Ping 1 lần. Trả về (online, thời gian ms hoặc None, TTL hoặc None)."""
    try:
        cmd = (["ping", "-n", "1", "-w", str(timeout_ms), ip] if IS_WIN
               else ["ping", "-c", "1", "-W", str(max(1, round(timeout_ms / 1000))), ip])
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           creationflags=NO_WINDOW, timeout=timeout_ms / 1000 + 3)
        txt = r.stdout.decode(errors="ignore")
        ttl = re.search(r"TTL[=:]\s*(\d+)", txt, re.I)
        # Windows trả mã 0 cả khi "Destination host unreachable" -> phải có TTL
        if r.returncode != 0 or not ttl:
            return False, None, None
        t = re.search(r"[=<]\s*([\d.]+)\s*ms", txt, re.I)
        return True, (round(float(t.group(1)), 1) if t else None), int(ttl.group(1))
    except Exception:
        return False, None, None


def ping(ip, timeout_ms=600):
    return ping_detail(ip, timeout_ms)[0]


def open_ports(ip, ports, timeout=0.6):
    found = []
    for p in ports:
        try:
            with socket.create_connection((ip, p), timeout=timeout):
                found.append(p)
        except OSError:
            pass
    return found


def arp_table():
    """Bảng ARP của máy tính: {ip: mac}. Chỉ có MAC của thiết bị cùng lớp mạng."""
    table = {}
    try:
        r = subprocess.run(["arp", "-a"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           creationflags=NO_WINDOW, timeout=10)
        for line in r.stdout.decode(errors="ignore").splitlines():
            m = re.search(r"(\d+\.\d+\.\d+\.\d+)\D+?([0-9a-fA-F]{2}([-:])(?:[0-9a-fA-F]{2}\3){4}[0-9a-fA-F]{2})", line)
            if m:
                table[m.group(1)] = norm_mac(m.group(2))
    except Exception:
        pass
    return table


def norm_mac(mac):
    h = re.sub(r"[^0-9a-fA-F]", "", mac or "")
    if len(h) != 12:
        return mac or ""
    return ":".join(h[i:i + 2] for i in range(0, 12, 2)).upper()


def hostname(ip):
    try:
        return socket.gethostbyaddr(ip)[0]
    except Exception:
        return ""


# ---------------------------------------------------------------- ONVIF
NS_WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
NS_WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
PW_DIGEST = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
B64 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"


def soap(body, user=None, password=None, time_offset=dt.timedelta(0)):
    header = ""
    if user:
        nonce = os.urandom(16)
        created = (dt.datetime.now(dt.timezone.utc) + time_offset).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        digest = base64.b64encode(hashlib.sha1(nonce + created.encode() + password.encode("utf-8")).digest()).decode()
        header = (
            f'<s:Header><Security s:mustUnderstand="1" xmlns="{NS_WSSE}"><UsernameToken>'
            f"<Username>{escape(user)}</Username>"
            f'<Password Type="{PW_DIGEST}">{digest}</Password>'
            f'<Nonce EncodingType="{B64}">{base64.b64encode(nonce).decode()}</Nonce>'
            f'<Created xmlns="{NS_WSU}">{created}</Created>'
            f"</UsernameToken></Security></s:Header>"
        )
    return ('<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">'
            f"{header}<s:Body>{body}</s:Body></s:Envelope>").encode("utf-8")


def tag(xml, name):
    m = re.search(r"<(?:\w+:)?%s\b[^>]*>(.*?)</(?:\w+:)?%s>" % (name, name), xml, re.S)
    return m.group(1).strip() if m else ""


class AuthError(Exception):
    pass


def onvif_call(ip, port, body, user, password, timeout=4, time_offset=dt.timedelta(0)):
    """Gửi lệnh ONVIF; dùng WS-Security, và HTTP Digest/Basic nếu camera yêu cầu."""
    url = f"http://{ip}:{port}/onvif/device_service"
    mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    mgr.add_password(None, url, user or "", password or "")
    opener = urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(mgr),
                                         urllib.request.HTTPBasicAuthHandler(mgr))
    req = urllib.request.Request(url, data=soap(body, user, password, time_offset), method="POST",
                                 headers={"Content-Type": "application/soap+xml; charset=utf-8"})
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.read().decode("utf-8", errors="ignore")
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", errors="ignore") if e.fp else ""
        if e.code in (401, 403) or re.search(r"NotAuthorized|Unauthorized|FailedAuthentication", txt, re.I):
            raise AuthError(txt)
        raise


def camera_time_offset(ip, port, timeout=4):
    """Lệch giờ giữa camera và máy tính (WS-Security cần giờ khớp)."""
    try:
        x = onvif_call(ip, port, '<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>',
                       None, None, timeout)
        utc = re.search(r"<(?:\w+:)?UTCDateTime>(.*?)</(?:\w+:)?UTCDateTime>", x, re.S)
        if not utc:
            return dt.timedelta(0)
        u = utc.group(1)
        cam = dt.datetime(int(tag(u, "Year")), int(tag(u, "Month")), int(tag(u, "Day")),
                          int(tag(u, "Hour")), int(tag(u, "Minute")), int(tag(u, "Second")), tzinfo=dt.timezone.utc)
        return cam - dt.datetime.now(dt.timezone.utc)
    except Exception:
        return dt.timedelta(0)


def query_camera(ip, port, user, password, timeout=4, offset=dt.timedelta(0)):
    x = onvif_call(ip, port, '<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>',
                   user, password, timeout, offset)
    info = {"Hãng": tag(x, "Manufacturer"), "Model": tag(x, "Model"), "Firmware": tag(x, "FirmwareVersion"),
            "Serial": tag(x, "SerialNumber"), "Hardware ID": tag(x, "HardwareId")}
    try:
        n = onvif_call(ip, port, '<GetNetworkInterfaces xmlns="http://www.onvif.org/ver10/device/wsdl"/>',
                       user, password, timeout, offset)
        info["MAC"] = norm_mac(tag(n, "HwAddress"))
    except Exception:
        pass
    return info


def scan_one(ip, ports, creds, timeout):
    """Trả về dict kết quả, hoặc None nếu không có thiết bị."""
    alive = ping(ip, int(timeout * 300))
    op = open_ports(ip, ports, timeout=min(1.0, timeout / 3))
    if not alive and not op:
        return None
    web_port = 80 if 80 in op else (op[0] if op else 80)
    row = {"IP": ip, "Trạng thái": "Có thiết bị, không thấy ONVIF",
           "_web": f"http://{ip}" + ("" if web_port == 80 else f":{web_port}")}
    creds = creds or [("", "")]
    for p in op:
        offset = camera_time_offset(ip, p, timeout)
        auth_failed = False
        for i, (user, pw) in enumerate(creds):
            try:
                info = query_camera(ip, p, user, pw, timeout, offset)
            except AuthError:
                auth_failed = True
                continue
            except Exception:
                break  # cổng này không phải ONVIF -> thử cổng khác
            row.update({k: v for k, v in info.items() if v})
            row["Cổng ONVIF"] = p
            row["Tài khoản"] = cred_label(creds, i)
            row["Trạng thái"] = "OK" if info.get("Serial") else "OK (camera không trả serial)"
            return row
        if auth_failed:
            row["Cổng ONVIF"] = p
            row["Trạng thái"] = ("Sai user/mật khẩu (hoặc lệch giờ camera)" if len(creds) == 1
                                 else f"Sai cả {len(creds)} tài khoản (hoặc lệch giờ camera)")
            return row
    return row


def run_parallel(ips, fn, workers, on_result=None, on_progress=None, stop=None):
    results, done = [], 0
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(fn, ip) for ip in ips]
        for f in as_completed(futs):
            if stop is not None and stop.is_set():
                for g in futs:
                    g.cancel()
                break
            done += 1
            try:
                r = f.result()
            except Exception:
                r = None
            if r:
                results.append(r)
                if on_result:
                    on_result(r)
            if on_progress:
                on_progress(done, len(ips))
    return results


def scan(ips, ports, creds, timeout=4.0, workers=64, on_result=None, on_progress=None, stop=None):
    results = run_parallel(ips, lambda ip: scan_one(ip, ports, creds, timeout), workers,
                           on_result, on_progress, stop)
    arp = arp_table()
    for r in results:
        if not r.get("MAC") and r["IP"] in arp:
            r["MAC"] = arp[r["IP"]]
    results.sort(key=lambda r: ip_key(r["IP"]))
    return results


class PingMonitor:
    """Ping nhiều vòng, cộng dồn thống kê cho từng IP (dùng cho ping 1 lần và ping liên tục)."""

    def __init__(self, resolve_names=False):
        self.state = {}
        self.resolve_names = resolve_names

    def round(self, ips, timeout_ms=1000, workers=64, on_progress=None, stop=None):
        results = run_parallel(ips, lambda ip: (ip, ping_detail(ip, timeout_ms)), workers,
                               None, on_progress, stop)
        now = dt.datetime.now().strftime("%H:%M:%S %d/%m")
        went_off, came_on = [], []
        for ip, (ok, ms, ttl) in results:
            s = self.state.setdefault(ip, {"IP": ip, "_web": f"http://{ip}", "_sent": 0, "_recv": 0,
                                           "_sum": 0.0, "_n": 0, "_ever": False})
            prev = s.get("Trạng thái")
            s["_sent"] += 1
            if ok:
                s["_recv"] += 1
                s["_ever"] = True
                s["Trạng thái"] = "Online"
                s["TTL"] = ttl
                s["Phản hồi (ms)"] = ms if ms is not None else ""
                if ms is not None:
                    s["_sum"] += ms
                    s["_n"] += 1
            else:
                s["Trạng thái"] = "Offline"
                s["Phản hồi (ms)"] = ""
            if prev and prev != s["Trạng thái"]:
                s["Đổi trạng thái"] = f"{now} → {s['Trạng thái']}"
                (came_on if ok else went_off).append(ip)
            s["Gửi/Nhận"] = f"{s['_sent']}/{s['_recv']}"
            s["Mất (%)"] = round(100 * (s["_sent"] - s["_recv"]) / s["_sent"])
            s["TB (ms)"] = round(s["_sum"] / s["_n"], 1) if s["_n"] else ""
        online = [s for s in self.state.values() if s.get("Trạng thái") == "Online"]
        need_mac = [s for s in online if not s.get("MAC")]
        if need_mac:
            arp = arp_table()
            for s in need_mac:
                if s["IP"] in arp:
                    s["MAC"] = arp[s["IP"]]
        if self.resolve_names:
            need = [s for s in online if "Tên máy" not in s]
            with ThreadPoolExecutor(max_workers=32) as ex:
                for s, name in zip(need, ex.map(lambda s: hostname(s["IP"]), need)):
                    s["Tên máy"] = name
        rows = sorted((dict(s) for s in self.state.values()), key=lambda r: ip_key(r["IP"]))
        return rows, went_off, came_on


def ping_sweep(ips, timeout_ms=1000, resolve_names=False, workers=64, on_progress=None, stop=None):
    return PingMonitor(resolve_names).round(ips, timeout_ms, workers, on_progress, stop)[0]


# ---------------------------------------------------------------- lưu thiết lập cho lần mở sau
def settings_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "QuetCamera")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "settings.json")


def load_settings():
    try:
        with open(settings_path(), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(data):
    try:
        with open(settings_path(), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    except Exception:
        pass


if IS_WIN:
    import ctypes

    class _Blob(ctypes.Structure):
        _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_char))]

    def _dpapi(data, encrypt):
        buf = ctypes.create_string_buffer(data, len(data))
        inp = _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
        out = _Blob()
        fn = ctypes.windll.crypt32.CryptProtectData if encrypt else ctypes.windll.crypt32.CryptUnprotectData
        if not fn(ctypes.byref(inp), None, None, None, None, 0x01, ctypes.byref(out)):
            raise OSError("DPAPI lỗi")
        try:
            return ctypes.string_at(out.pbData, out.cbData)
        finally:
            ctypes.windll.kernel32.LocalFree(ctypes.cast(out.pbData, ctypes.c_void_p))


def protect(text):
    """Mã hoá mật khẩu theo tài khoản Windows (DPAPI) trước khi lưu ra file."""
    if IS_WIN:
        try:
            return "dpapi:" + base64.b64encode(_dpapi(text.encode("utf-8"), True)).decode()
        except Exception:
            pass
    return "b64:" + base64.b64encode(text.encode("utf-8")).decode()


def unprotect(s):
    try:
        kind, _, data = (s or "").partition(":")
        raw = base64.b64decode(data)
        if kind == "dpapi":
            return _dpapi(raw, False).decode("utf-8")
        if kind == "b64":
            return raw.decode("utf-8")
    except Exception:
        pass
    return ""


# ---------------------------------------------------------------- tự cập nhật
def version_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", str(v))) or (0,)


def check_update(timeout=6):
    """Trả về dict thông tin bản mới nếu có bản mới hơn, None nếu đang là bản mới nhất."""
    req = urllib.request.Request(UPDATE_URL, headers={"User-Agent": "QuetCamera-updater",
                                                      "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        info = json.loads(r.read().decode("utf-8"))
    if APP_VERSION == "dev" or version_tuple(info.get("version")) > version_tuple(APP_VERSION):
        return info
    return None


def download_update(info, dest_dir, on_progress=None):
    """Tải file exe mới, kiểm tra SHA-256. Trả về đường dẫn file đã tải."""
    path = os.path.join(dest_dir, "QuetCamera.new.exe")
    h = hashlib.sha256()
    req = urllib.request.Request(info["url"], headers={"User-Agent": "QuetCamera-updater"})
    with urllib.request.urlopen(req, timeout=30) as r, open(path, "wb") as f:
        total = int(r.headers.get("Content-Length") or info.get("size") or 0)
        got = 0
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
            h.update(chunk)
            got += len(chunk)
            if on_progress:
                on_progress(got, total)
    if info.get("sha256") and h.hexdigest().lower() != info["sha256"].lower():
        os.remove(path)
        raise ValueError("File tải về bị lỗi (sai mã kiểm tra). Hãy thử lại.")
    return path


def schedule_replace(new_path, target_path, pid, restart_cmd=None):
    """Windows: chạy 1 file .bat ẩn, đợi chương trình hiện tại thoát rồi thay file và mở lại.
    Ghi nhật ký vào %TEMP%\\quetcamera_update.log để tra lỗi."""
    tmp = tempfile.gettempdir()
    bat = os.path.join(tmp, f"quetcamera_update_{pid}.bat")
    log = os.path.join(tmp, "quetcamera_update.log")
    restart = f'start "" {restart_cmd}' if restart_cmd else f'start "" "{target_path}"'
    lines = [
        "@echo off",
        "setlocal EnableDelayedExpansion",
        f'echo [%date% %time%] bat bat dau, cho PID {pid} thoat > "{log}"',
        ":wait",
        f'tasklist /FI "PID eq {pid}" /NH 2>nul | find " {pid} " >nul',
        "if not errorlevel 1 (ping -n 2 127.0.0.1 >nul & goto wait)",
        f'echo [%time%] chuong trinh da thoat >> "{log}"',
        "set n=0",
        ":move",
        f'move /y "{new_path}" "{target_path}" >> "{log}" 2>&1',
        "if errorlevel 1 (",
        "  set /a n+=1",
        f'  echo [%time%] file dang bi khoa, thu lai !n! >> "{log}"',
        "  if !n! lss 30 (ping -n 2 127.0.0.1 >nul & goto move)",
        f'  echo [%time%] KHONG thay duoc file >> "{log}"',
        "  goto end",
        ")",
        f'echo [%time%] da thay file, mo lai >> "{log}"',
        restart,
        ":end",
        'del "%~f0"',
    ]
    with open(bat, "w", encoding="mbcs" if IS_WIN else "utf-8", newline="\r\n") as f:
        f.write("\n".join(lines) + "\n")
    # Cửa sổ ẩn (CREATE_NO_WINDOW) + nhóm tiến trình riêng để không bị tắt theo chương trình chính
    subprocess.Popen(["cmd", "/c", bat], creationflags=NO_WINDOW | 0x00000200, close_fds=True)
    return bat


# ---------------------------------------------------------------- xuất Excel (.xlsx, không cần thư viện)
def write_xlsx(path, rows, columns, title="Danh sách"):
    def cell(ref, v, s=0):
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            return f'<c r="{ref}" s="{s}"><v>{v}</v></c>'
        return f'<c r="{ref}" s="{s}" t="inlineStr"><is><t xml:space="preserve">{escape(str(v))}</t></is></c>'

    def col(i):
        s, i = "", i + 1
        while i:
            i, r = divmod(i - 1, 26)
            s = chr(65 + r) + s
        return s

    data = [columns] + [[i + 1] + [r.get(c, "") for c in columns[1:]] for i, r in enumerate(rows)]
    widths = [max(6, min(40, max(len(str(row[ci])) for row in data) + 2)) for ci in range(len(columns))]
    last = f"{col(len(columns) - 1)}{len(data)}"
    sheet = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>']
    sheet += [f'<col min="{i + 1}" max="{i + 1}" width="{w}" customWidth="1"/>' for i, w in enumerate(widths)]
    sheet.append("</cols><sheetData>")
    for ri, row in enumerate(data):
        sheet.append(f'<row r="{ri + 1}">' + "".join(cell(f"{col(ci)}{ri + 1}", v, 1 if ri == 0 else 0)
                                                    for ci, v in enumerate(row)) + "</row>")
    sheet.append(f'</sheetData><autoFilter ref="A1:{last}"/></worksheet>')
    styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
              '<fonts count="2"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts>'
              '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
              '<fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill></fills>'
              '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
              '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
              '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
              '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>'
              '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
    t = escape(title[:31])
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                   '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
                   '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
                   f'<sheet name="{t}" sheetId="1" r:id="rId1"/></sheets>'
                   f'<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">\'{t}\'!$A$1:${col(len(columns) - 1)}${len(data)}</definedName></definedNames></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
                   '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
        z.writestr("xl/styles.xml", styles)
        z.writestr("xl/worksheets/sheet1.xml", "".join(sheet))


# ---------------------------------------------------------------- sắp xếp nhiều cột
def sort_rows(rows, keys):
    """keys: [(cột, giảm_dần), ...] – phần tử đầu là ưu tiên cao nhất. Ô trống luôn nằm cuối."""
    out = sorted(rows, key=lambda r: ip_key(r.get("IP", "")))   # mặc định / phân định cuối: theo IP
    for col, rev in reversed(keys):
        def k(r, c=col):
            v = r.get(c, "")
            if c == "IP":
                return ip_key(v)
            if c in NUMERIC_COLS:
                try:
                    return float(v)
                except (TypeError, ValueError):
                    return float("-inf")
            return str(v).lower()
        filled = [r for r in out if r.get(col, "") not in ("", None)]
        blank = [r for r in out if r.get(col, "") in ("", None)]
        out = sorted(filled, key=k, reverse=rev) + blank
    return out


# ---------------------------------------------------------------- giao diện
def run_gui():
    import tkinter as tk
    from tkinter import ttk, messagebox, filedialog

    cfg = load_settings()
    root = tk.Tk()
    root.title(f"{APP_NAME} – IP, MAC, Serial (ONVIF)   ·   phiên bản {APP_VERSION}")
    root.geometry(cfg.get("geometry") or "1280x720")
    try:
        root.tk.call("tk", "scaling", 1.3)
    except Exception:
        pass
    globe = tk.PhotoImage(data=GLOBE_PNG)
    q = queue.Queue()
    EXAMPLES = ("VD: 192.168.1.1-254   |   192.168.1.10-192.168.2.50   |   10.0.0.0/24   |   "
                "192.168.1.25, 192.168.5.1-100  (nhiều dải cách nhau dấu phẩy)")

    def remember(history, value):
        h = [value] + [x for x in history if x != value]
        return h[:12]

    class SortTable:
        """Bảng kết quả. Cột IP có quả cầu: di chuột hiện bàn tay, bấm để mở web.
        Bấm tiêu đề để sắp xếp (bấm nhiều cột = sắp theo nhiều cột); nhấp đúp dòng cũng mở web."""

        def __init__(self, parent, columns, widths, row_tag=None):
            self.columns = columns                                   # dùng khi xuất Excel
            self.view = [c for c in columns if c not in ("STT", "IP")]
            self.rows, self.keys, self.shown, self.filter, self.row_tag = [], [], [], None, row_tag
            frame = ttk.Frame(parent)
            frame.pack(fill="both", expand=True)
            self.tv = ttk.Treeview(frame, columns=self.view, show="tree headings")
            self.tv.heading("#0", text="IP", command=lambda: self.click_header("IP"))
            self.tv.column("#0", width=widths.get("IP", 150), stretch=False, anchor="w")
            for c in self.view:
                self.tv.heading(c, text=c, command=lambda c=c: self.click_header(c))
                self.tv.column(c, width=widths.get(c, 110), anchor="w")
            self.tv.tag_configure("on", foreground="#1B7F2A")
            self.tv.tag_configure("off", foreground="#C62828", background="#FDECEA")
            sb = ttk.Scrollbar(frame, orient="vertical", command=self.tv.yview)
            self.tv.configure(yscrollcommand=sb.set)
            sb.pack(side="right", fill="y")
            self.tv.pack(side="left", fill="both", expand=True)
            self.tv.bind("<Motion>", self.on_motion)
            self.tv.bind("<Leave>", lambda e: self.tv.configure(cursor=""))
            self.tv.bind("<ButtonRelease-1>", self.on_click)
            self.tv.bind("<Double-1>", self.on_double)

        # --- mở web ---
        def _ip_cell(self, e):
            return (self.tv.identify_row(e.y) and self.tv.identify_column(e.x) == "#0"
                    and self.tv.identify_region(e.x, e.y) in ("tree", "cell"))

        def on_motion(self, e):
            self.tv.configure(cursor="hand2" if self._ip_cell(e) else "")

        def open_row(self, item):
            r = self.shown[int(item)]
            webbrowser.open(r.get("_web") or f"http://{r['IP']}")

        def on_click(self, e):
            if self._ip_cell(e):
                self.open_row(self.tv.identify_row(e.y))

        def on_double(self, e):
            item = self.tv.identify_row(e.y)
            if item and not self._ip_cell(e) and self.tv.identify_region(e.x, e.y) == "cell":
                self.open_row(item)

        # --- sắp xếp ---
        def click_header(self, col):
            if self.keys and self.keys[0][0] == col:
                self.keys[0] = (col, not self.keys[0][1])             # bấm lại cột chính: đảo chiều
            else:
                self.keys = ([(col, False)] + [k for k in self.keys if k[0] != col])[:4]
            self.render()

        def reset_sort(self):
            self.keys = []
            self.render()

        def set_rows(self, rows):
            self.rows = list(rows)
            self.render()

        def add_row(self, row):
            self.rows.append(row)

        def visible(self):
            return sort_rows([r for r in self.rows if self.filter is None or self.filter(r)], self.keys)

        def render(self):
            def mark(c):
                for i, (k, rev) in enumerate(self.keys):
                    if k == c:
                        return f"  {'▼' if rev else '▲'}{i + 1 if len(self.keys) > 1 else ''}"
                return ""
            self.tv.heading("#0", text="IP" + mark("IP"))
            for c in self.view:
                self.tv.heading(c, text=c + mark(c))
            top = self.tv.yview()[0]
            self.tv.delete(*self.tv.get_children())
            self.shown = self.visible()
            for i, r in enumerate(self.shown):
                tag = self.row_tag(r) if self.row_tag else ""
                self.tv.insert("", "end", iid=str(i), text=" " + r.get("IP", ""), image=globe,
                               values=[r.get(c, "") for c in self.view], tags=(tag,) if tag else ())
            self.tv.yview_moveto(top)

        def export(self, title, prefix):
            if not self.rows:
                return None
            name = prefix + dt.datetime.now().strftime("%Y%m%d_%H%M") + ".xlsx"
            path = filedialog.asksaveasfilename(defaultextension=".xlsx", initialfile=name,
                                                filetypes=[("Excel", "*.xlsx")])
            if path:
                write_xlsx(path, self.visible(), self.columns, title)
                try:
                    os.startfile(path)
                except Exception:
                    pass
                return path
            return None

    # ===================== THANH TRÊN: TÊN, PHIÊN BẢN, CẬP NHẬT, GIỚI THIỆU =====================
    header = ttk.Frame(root)
    header.pack(fill="x", padx=8, pady=(8, 0))
    v_upd = tk.StringVar(value=f"Phiên bản {APP_VERSION}")
    b_about = ttk.Button(header, text="ⓘ Giới thiệu")
    b_about.pack(side="right", padx=(4, 0))
    b_upd = ttk.Button(header, text="⟳ Kiểm tra cập nhật")
    b_upd.pack(side="right")
    ttk.Label(header, textvariable=v_upd, foreground="#555").pack(side="right", padx=(0, 10))
    ttk.Label(header, text=APP_NAME.upper() + " – IP, MAC, Serial camera & Ping dải IP",
              font=("Segoe UI", 11, "bold")).pack(side="left")
    nb = ttk.Notebook(root)
    nb.pack(fill="both", expand=True, padx=8, pady=(4, 8))

    # ===================== TAB 1: QUÉT CAMERA =====================
    t1 = ttk.Frame(nb, padding=8)
    nb.add(t1, text="  Quét camera (ONVIF)  ")
    top = ttk.Frame(t1)
    top.pack(fill="x")
    cam_hist = cfg.get("cam_history") or ["192.168.1.1-254"]
    v_range = tk.StringVar(value=cfg.get("cam_range") or "192.168.1.1-254")
    v_ports = tk.StringVar(value=cfg.get("cam_ports") or DEFAULT_PORTS)
    v_status = tk.StringVar(value="Nhập dải IP và các tài khoản camera rồi bấm Quét.")
    ttk.Label(top, text="Dải IP:").grid(row=0, column=0, sticky="w")
    cb_range = ttk.Combobox(top, textvariable=v_range, values=cam_hist, width=46)
    cb_range.grid(row=0, column=1, sticky="w", padx=4)
    ttk.Label(top, text=EXAMPLES, foreground="#666", wraplength=420, justify="left").grid(
        row=1, column=1, sticky="w", padx=4)
    ttk.Label(top, text="Cổng ONVIF:").grid(row=2, column=0, sticky="w", pady=(6, 0))
    ttk.Entry(top, textvariable=v_ports, width=48).grid(row=2, column=1, sticky="w", padx=4, pady=(6, 0))
    ttk.Label(top, text="Tài khoản (mỗi dòng 1 tài khoản)\nuser:pass  |  user pass  |  user/pass").grid(
        row=0, column=2, rowspan=3, sticky="ne", padx=(16, 4))
    creds_box = tk.Text(top, width=30, height=5, font=("Consolas", 10))
    creds_box.insert("1.0", unprotect(cfg.get("cam_creds", "")) or "admin:\n")
    creds_box.grid(row=0, column=3, rowspan=3, sticky="nw")
    btns = ttk.Frame(top)
    btns.grid(row=0, column=4, rowspan=3, sticky="n", padx=(12, 0))
    b_scan = ttk.Button(btns, text="▶ Quét")
    b_stop = ttk.Button(btns, text="■ Dừng", state="disabled")
    b_save = ttk.Button(btns, text="💾 Xuất Excel", state="disabled")
    b_sort = ttk.Button(btns, text="↺ Sắp theo IP")
    for i, b in enumerate([b_scan, b_stop, b_save, b_sort]):
        b.grid(row=i // 2, column=i % 2, padx=3, pady=2, sticky="ew")
    ttk.Label(t1, text="Bấm vào IP (biểu tượng quả cầu) để mở trang web. Bấm tiêu đề cột để sắp xếp – "
                       "bấm thêm cột khác để sắp theo nhiều cột (vd bấm Model rồi bấm Hãng = theo Hãng, cùng hãng theo Model).",
              foreground="#555").pack(fill="x", pady=(8, 4))
    cam = SortTable(t1, CAM_COLUMNS, {"IP": 150, "MAC": 140, "Hãng": 100, "Model": 140, "Firmware": 160,
                                      "Serial": 180, "Hardware ID": 100, "Cổng ONVIF": 80,
                                      "Tài khoản": 140, "Trạng thái": 240})
    bar1 = ttk.Progressbar(t1, mode="determinate")
    bar1.pack(fill="x", pady=(6, 0))
    ttk.Label(t1, textvariable=v_status).pack(fill="x")
    st1 = {"stop": None}

    def scan_start():
        try:
            ips = parse_ranges(v_range.get())
            ports = [int(p) for p in re.split(r"[,;\s]+", v_ports.get().strip()) if p]
            creds = parse_creds(creds_box.get("1.0", "end"))
        except Exception as e:
            messagebox.showerror("Dữ liệu không hợp lệ", str(e))
            return
        if not creds:
            messagebox.showerror("Thiếu tài khoản", "Nhập ít nhất một tài khoản (user:pass, user pass hoặc user/pass).")
            return
        if len(ips) > 4096 and not messagebox.askyesno("Dải IP lớn", f"Sẽ quét {len(ips)} địa chỉ. Tiếp tục?"):
            return
        cb_range["values"] = remember(list(cb_range["values"]), v_range.get().strip())
        persist()
        cam.set_rows([])
        b_scan["state"], b_stop["state"], b_save["state"] = "disabled", "normal", "disabled"
        stop = threading.Event()
        st1["stop"] = stop

        def work():
            try:
                res = scan(ips, ports, creds, on_result=lambda r: q.put(("cam_row", r)),
                           on_progress=lambda d, t: q.put(("cam_prog", (d, t))), stop=stop)
                q.put(("cam_done", res))
            except Exception as e:
                q.put(("error", str(e)))

        threading.Thread(target=work, daemon=True).start()

    def scan_stop():
        if st1["stop"]:
            st1["stop"].set()
            v_status.set("Đang dừng…")

    def cam_save():
        p = cam.export("Danh sách camera", "DanhSachCamera_")
        if p:
            v_status.set("Đã lưu: " + p)

    b_scan["command"] = scan_start
    b_stop["command"] = scan_stop
    b_save["command"] = cam_save
    b_sort["command"] = cam.reset_sort

    # ===================== TAB 2: PING DẢI IP =====================
    t2 = ttk.Frame(nb, padding=8)
    nb.add(t2, text="  Ping dải IP  ")
    top2 = ttk.Frame(t2)
    top2.pack(fill="x")
    ping_hist = cfg.get("ping_history") or ["192.168.1.1-254"]
    v_prange = tk.StringVar(value=cfg.get("ping_range") or "192.168.1.1-254")
    v_ptimeout = tk.StringVar(value=str(cfg.get("ping_timeout", 1000)))
    v_online = tk.BooleanVar(value=cfg.get("ping_only_seen", True))
    v_names = tk.BooleanVar(value=cfg.get("ping_names", False))
    v_loop = tk.BooleanVar(value=cfg.get("ping_loop", False))
    v_interval = tk.StringVar(value=str(cfg.get("ping_interval", 5)))
    v_beep = tk.BooleanVar(value=cfg.get("ping_beep", True))
    v_pstatus = tk.StringVar(value="Nhập dải IP rồi bấm Ping.")
    ttk.Label(top2, text="Dải IP:").grid(row=0, column=0, sticky="w")
    cb_prange = ttk.Combobox(top2, textvariable=v_prange, values=ping_hist, width=46)
    cb_prange.grid(row=0, column=1, sticky="w", padx=4)
    ttk.Label(top2, text=EXAMPLES, foreground="#666", wraplength=420, justify="left").grid(
        row=1, column=1, sticky="w", padx=4)
    opt = ttk.Frame(top2)
    opt.grid(row=0, column=2, rowspan=3, sticky="nw", padx=(16, 0))
    ttk.Label(opt, text="Chờ tối đa (ms):").grid(row=0, column=0, sticky="w")
    ttk.Entry(opt, textvariable=v_ptimeout, width=7).grid(row=0, column=1, sticky="w", padx=4)
    ttk.Checkbutton(opt, text="Ping liên tục, mỗi", variable=v_loop).grid(row=1, column=0, sticky="w", pady=(4, 0))
    f_int = ttk.Frame(opt)
    f_int.grid(row=1, column=1, sticky="w", pady=(4, 0))
    ttk.Entry(f_int, textvariable=v_interval, width=5).pack(side="left", padx=4)
    ttk.Label(f_int, text="giây").pack(side="left")
    ttk.Checkbutton(opt, text="Kêu bíp khi có IP mất kết nối", variable=v_beep).grid(row=2, column=0, columnspan=2, sticky="w")
    ttk.Checkbutton(opt, text="Ẩn IP chưa từng online", variable=v_online).grid(row=0, column=2, sticky="w", padx=(16, 0))
    ttk.Checkbutton(opt, text="Tra tên máy (chậm hơn)", variable=v_names).grid(row=1, column=2, sticky="w", padx=(16, 0), pady=(4, 0))
    btns2 = ttk.Frame(top2)
    btns2.grid(row=0, column=3, rowspan=3, sticky="n", padx=(16, 0))
    p_go = ttk.Button(btns2, text="▶ Ping")
    p_stop = ttk.Button(btns2, text="■ Dừng", state="disabled")
    p_save = ttk.Button(btns2, text="💾 Xuất Excel", state="disabled")
    p_sort = ttk.Button(btns2, text="↺ Sắp theo IP")
    for i, b in enumerate([p_go, p_stop, p_save, p_sort]):
        b.grid(row=i // 2, column=i % 2, padx=3, pady=2, sticky="ew")
    ttk.Label(t2, text="Xanh = online, đỏ = mất kết nối. Bấm vào IP để mở web. Bấm tiêu đề cột để sắp xếp "
                       "(vd bấm Trạng thái để đưa các IP mất kết nối lên đầu).",
              foreground="#555").pack(fill="x", pady=(8, 4))
    pt = SortTable(t2, PING_COLUMNS, {"IP": 150, "Trạng thái": 80, "Phản hồi (ms)": 100, "TB (ms)": 70,
                                      "Mất (%)": 65, "Gửi/Nhận": 80, "TTL": 50, "MAC": 140,
                                      "Tên máy": 200, "Đổi trạng thái": 170},
                   row_tag=lambda r: "on" if r.get("Trạng thái") == "Online" else ("off" if r.get("_ever") else ""))
    pt.filter = lambda r: (not v_online.get()) or r.get("_ever")
    v_online.trace_add("write", lambda *a: pt.render())
    bar2 = ttk.Progressbar(t2, mode="determinate")
    bar2.pack(fill="x", pady=(6, 0))
    ttk.Label(t2, textvariable=v_pstatus).pack(fill="x")
    st2 = {"stop": None}

    def ping_start():
        try:
            ips = parse_ranges(v_prange.get())
            tmo = int(v_ptimeout.get())
            interval = max(1.0, float(v_interval.get().replace(",", ".")))
        except Exception as e:
            messagebox.showerror("Dữ liệu không hợp lệ", str(e))
            return
        cb_prange["values"] = remember(list(cb_prange["values"]), v_prange.get().strip())
        persist()
        pt.set_rows([])
        p_go["state"], p_stop["state"], p_save["state"] = "disabled", "normal", "disabled"
        stop = threading.Event()
        st2["stop"] = stop
        loop, names = v_loop.get(), v_names.get()

        def work():
            try:
                mon = PingMonitor(names)
                n = 0
                while True:
                    n += 1
                    rows, off, on = mon.round(ips, tmo, on_progress=lambda d, t: q.put(("ping_prog", (d, t, n))),
                                              stop=stop)
                    q.put(("ping_round", (rows, n, off, on, loop)))
                    if not loop or stop.is_set() or stop.wait(interval):
                        break
                q.put(("ping_done", None))
            except Exception as e:
                q.put(("error", str(e)))

        threading.Thread(target=work, daemon=True).start()

    def ping_stop():
        if st2["stop"]:
            st2["stop"].set()
            v_pstatus.set("Đang dừng…")

    def ping_save():
        p = pt.export("Ping dải IP", "Ping_")
        if p:
            v_pstatus.set("Đã lưu: " + p)

    p_go["command"] = ping_start
    p_stop["command"] = ping_stop
    p_save["command"] = ping_save
    p_sort["command"] = pt.reset_sort
    try:
        nb.select(int(cfg.get("tab", 0)))
    except Exception:
        pass

    # ===================== LƯU THIẾT LẬP =====================
    def persist():
        save_settings({
            "geometry": root.geometry(), "tab": nb.index(nb.select()),
            "cam_range": v_range.get().strip(), "cam_history": list(cb_range["values"]),
            "cam_ports": v_ports.get().strip(), "cam_creds": protect(creds_box.get("1.0", "end").strip()),
            "ping_range": v_prange.get().strip(), "ping_history": list(cb_prange["values"]),
            "ping_timeout": v_ptimeout.get().strip(), "ping_only_seen": v_online.get(),
            "ping_names": v_names.get(), "ping_loop": v_loop.get(), "ping_interval": v_interval.get().strip(),
            "ping_beep": v_beep.get(),
        })

    def on_close():
        persist()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)

    # ===================== GIỚI THIỆU, CẬP NHẬT =====================
    def show_about():
        win = tk.Toplevel(root)
        win.title("Giới thiệu")
        win.resizable(False, False)
        win.transient(root)
        f = ttk.Frame(win, padding=20)
        f.pack()
        ttk.Label(f, text=APP_NAME.upper(), font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(f, text="Quét IP, MAC, serial camera (ONVIF) và ping dải IP").pack(anchor="w", pady=(2, 10))
        ttk.Label(f, text=f"Phiên bản: {APP_VERSION}").pack(anchor="w")
        ttk.Label(f, text=f"Tác giả: {APP_AUTHOR}").pack(anchor="w")
        mail = ttk.Label(f, text=f"Email: {APP_EMAIL}", foreground="#0563C1", cursor="hand2")
        mail.pack(anchor="w")
        mail.bind("<Button-1>", lambda e: webbrowser.open(f"mailto:{APP_EMAIL}"))
        ttk.Button(f, text="Đóng", command=win.destroy).pack(anchor="e", pady=(14, 0))
        win.grab_set()

    b_about["command"] = show_about

    def check_async(manual):
        def work():
            try:
                q.put(("upd_info", (check_update(), manual)))
            except Exception as e:
                q.put(("upd_err", (str(e), manual)))
        threading.Thread(target=work, daemon=True).start()

    def do_update(info):
        if not getattr(sys, "frozen", False) or not IS_WIN:
            messagebox.showinfo("Cập nhật", "Đang chạy bằng file .py – hãy tải bản mới tại:\n" + info["url"])
            return
        dest = os.path.dirname(sys.executable)
        if not os.access(dest, os.W_OK):
            dest = tempfile.gettempdir()
        v_upd.set("Đang tải bản mới…")
        b_upd["state"] = "disabled"

        def work():
            try:
                new = download_update(info, dest, lambda g, t: q.put(("upd_prog", (g, t))))
                q.put(("upd_ready", new))
            except Exception as e:
                q.put(("upd_err", (str(e), True)))
        threading.Thread(target=work, daemon=True).start()

    def manual_check():
        v_upd.set("Đang kiểm tra cập nhật…")
        check_async(True)

    b_upd["command"] = manual_check

    # ===================== VÒNG XỬ LÝ SỰ KIỆN =====================
    def poll():
        dirty = False
        try:
            while True:
                kind, a = q.get_nowait()
                if kind == "cam_row":
                    cam.add_row(a)
                    dirty = True
                elif kind == "cam_prog":
                    bar1["maximum"], bar1["value"] = a[1], a[0]
                    v_status.set(f"Đang quét {a[0]}/{a[1]} địa chỉ… tìm thấy {len(cam.rows)} thiết bị")
                elif kind == "cam_done":
                    cam.set_rows(a)
                    ok = sum(1 for r in a if r.get("Serial"))
                    v_status.set(f"Xong: {len(a)} thiết bị trả lời, {ok} camera lấy được serial.")
                    b_scan["state"], b_stop["state"] = "normal", "disabled"
                    b_save["state"] = "normal" if a else "disabled"
                elif kind == "ping_prog":
                    d, t, n = a
                    bar2["maximum"], bar2["value"] = t, d
                    if n == 1:
                        v_pstatus.set(f"Đang ping {d}/{t}…")
                elif kind == "ping_round":
                    rows, n, off, on, loop = a
                    pt.set_rows(rows)
                    p_save["state"] = "normal"
                    online = sum(1 for r in rows if r.get("Trạng thái") == "Online")
                    seen = sum(1 for r in rows if r.get("_ever"))
                    lost = seen - online
                    msg = f"{'Vòng ' + str(n) + ' – ' if loop else ''}{dt.datetime.now():%H:%M:%S}: " \
                          f"online {online}/{len(rows)}"
                    if loop:
                        msg += f", đang mất kết nối {lost}"
                        if off:
                            msg += f"  ⚠ vừa mất: {', '.join(off[:5])}{'…' if len(off) > 5 else ''}"
                            if v_beep.get():
                                root.bell()
                        if on:
                            msg += f"  ✓ có lại: {', '.join(on[:5])}{'…' if len(on) > 5 else ''}"
                    v_pstatus.set(msg)
                elif kind == "ping_done":
                    p_go["state"], p_stop["state"] = "normal", "disabled"
                    v_pstatus.set(v_pstatus.get() + "   (đã dừng)" if v_loop.get() else v_pstatus.get())
                elif kind == "upd_info":
                    info, manual = a
                    if info:
                        v_upd.set(f"Phiên bản {APP_VERSION} – có bản mới {info.get('version')}")
                        if messagebox.askyesno("Có bản cập nhật",
                                               f"Có phiên bản mới {info.get('version')} (đang dùng {APP_VERSION}).\n\n"
                                               f"{info.get('notes', '')}\n\nCập nhật ngay? Chương trình sẽ tự mở lại."):
                            do_update(info)
                    else:
                        v_upd.set(f"Phiên bản {APP_VERSION} – đang là bản mới nhất")
                        if manual:
                            messagebox.showinfo("Cập nhật", "Bạn đang dùng phiên bản mới nhất.")
                elif kind == "upd_err":
                    msg, manual = a
                    v_upd.set(f"Phiên bản {APP_VERSION} – không kiểm tra được cập nhật")
                    b_upd["state"] = "normal"
                    if manual:
                        messagebox.showwarning("Cập nhật", "Không kiểm tra/tải được bản mới:\n" + msg)
                elif kind == "upd_prog":
                    g, t = a
                    v_upd.set(f"Đang tải bản mới… {g * 100 // t if t else 0}%")
                elif kind == "upd_ready":
                    persist()
                    schedule_replace(a, sys.executable, os.getpid())
                    root.destroy()
                    return
                elif kind == "error":
                    messagebox.showerror("Lỗi", a)
                    b_scan["state"], b_stop["state"] = "normal", "disabled"
                    p_go["state"], p_stop["state"] = "normal", "disabled"
        except queue.Empty:
            pass
        if dirty:
            cam.render()
        root.after(200, poll)

    root.after(200, poll)
    if APP_VERSION != "dev":
        root.after(1500, lambda: check_async(False))   # tự kiểm tra bản mới khi mở
    root.mainloop()


# ---------------------------------------------------------------- dòng lệnh
def main():
    # Bản .exe không có cửa sổ dòng lệnh: chuyển print sang "hư không" để khỏi lỗi
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
    # Cửa sổ dòng lệnh Windows (cp1252/cp1258) không in được tiếng Việt -> ép UTF-8
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description=f"{APP_NAME} {APP_VERSION} – {APP_AUTHOR} <{APP_EMAIL}>")
    ap.add_argument("--range", help="Dải IP cần quét camera, vd 192.168.1.1-254")
    ap.add_argument("--cred", action="append", default=[], help="Tài khoản user:mật khẩu (lặp lại được)")
    ap.add_argument("--user", help="(cũ) user")
    ap.add_argument("--password", default="", help="(cũ) mật khẩu")
    ap.add_argument("--ports", default=DEFAULT_PORTS)
    ap.add_argument("--timeout", type=float, default=4.0)
    ap.add_argument("--ping", help="Ping dải IP, vd 192.168.1.1-254")
    ap.add_argument("--names", action="store_true", help="Tra tên máy khi ping")
    ap.add_argument("--out", help="File Excel kết quả (.xlsx)")
    ap.add_argument("--version", action="store_true")
    a = ap.parse_args()
    if a.version:
        print(APP_VERSION)
        return
    if a.ping:
        ips = parse_ranges(a.ping)
        res = ping_sweep(ips, int(a.timeout * 250), a.names)
        for r in res:
            if r["Trạng thái"] == "Online":
                print(" | ".join(str(r.get(c, "")) for c in PING_COLUMNS[1:]))
        print(f"Online {sum(r['Trạng thái'] == 'Online' for r in res)}/{len(res)}")
        if a.out:
            write_xlsx(a.out, [r for r in res if r["Trạng thái"] == "Online"], PING_COLUMNS, "Ping dải IP")
        return
    if not a.range:
        run_gui()
        return
    creds = [split_cred(c) for c in a.cred]
    if a.user is not None:
        creds.insert(0, (a.user, a.password))
    ips = parse_ranges(a.range)
    ports = [int(p) for p in re.split(r"[,;\s]+", a.ports.strip()) if p]
    print(f"Quét {len(ips)} địa chỉ, cổng {ports}, {len(creds)} tài khoản …")
    res = scan(ips, ports, creds, a.timeout, on_progress=lambda d, t: print(f"\r  {d}/{t}", end="", flush=True))
    print()
    for r in res:
        print(" | ".join(str(r.get(c, "")) for c in CAM_COLUMNS[1:]))
    if a.out:
        write_xlsx(a.out, res, CAM_COLUMNS, "Danh sách camera")
        print("Đã lưu", a.out)


if __name__ == "__main__":
    main()

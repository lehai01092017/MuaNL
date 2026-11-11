# -*- coding: utf-8 -*-
"""
QUÉT CAMERA – lấy IP, MAC, hãng, model, firmware, serial của camera IP qua chuẩn ONVIF.

Chạy:   nhấp đúp "Chay_QuetCamera.bat"   (hoặc: python QuetCamera.py)
Dòng lệnh (không giao diện):
        python QuetCamera.py --range 192.168.1.1-254 --user admin --password 123456 --out ketqua.xlsx

Dải IP nhập được nhiều kiểu, cách nhau bằng dấu phẩy:
        192.168.1.1-254          192.168.1.10-192.168.2.50          192.168.1.0/24          192.168.1.25
Chỉ dùng thư viện có sẵn của Python 3 (không cần cài thêm gì).
"""
import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import os
import platform
import queue
import re
import socket
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from xml.sax.saxutils import escape

DEFAULT_PORTS = "80,8000,8080,8899,2020"
COLUMNS = ["STT", "IP", "MAC", "Hãng", "Model", "Firmware", "Serial", "Hardware ID", "Cổng ONVIF", "Trạng thái"]


# ---------------------------------------------------------------- dải IP
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
            hosts = list(net.hosts()) or [net.network_address]
            for ip in hosts:
                add(ip)
        elif "-" in part:
            a, b = part.split("-", 1)
            start = ipaddress.ip_address(a.strip())
            b = b.strip()
            if "." in b:
                end = ipaddress.ip_address(b)
            else:  # 192.168.1.1-254
                end = ipaddress.ip_address(".".join(a.strip().split(".")[:3] + [b]))
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


# ---------------------------------------------------------------- dò thiết bị
IS_WIN = platform.system() == "Windows"
NO_WINDOW = 0x08000000 if IS_WIN else 0  # không bật cửa sổ đen khi gọi ping/arp


def ping(ip, timeout_ms=600):
    try:
        if IS_WIN:
            cmd = ["ping", "-n", "1", "-w", str(timeout_ms), ip]
        else:
            cmd = ["ping", "-c", "1", "-W", str(max(1, timeout_ms // 1000)), ip]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           creationflags=NO_WINDOW, timeout=timeout_ms / 1000 + 2)
        txt = r.stdout.decode(errors="ignore")
        # Windows trả mã 0 cả khi "Destination host unreachable" -> kiểm tra TTL
        return r.returncode == 0 and ("TTL=" in txt.upper() or not IS_WIN)
    except Exception:
        return False


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
    """Đọc bảng ARP của máy tính: {ip: mac}. Chỉ có MAC của thiết bị cùng lớp mạng."""
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
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">'
        f"{header}<s:Body>{body}</s:Body></s:Envelope>"
    ).encode("utf-8")


def tag(xml, name):
    m = re.search(r"<(?:\w+:)?%s\b[^>]*>(.*?)</(?:\w+:)?%s>" % (name, name), xml, re.S)
    return m.group(1).strip() if m else ""


class AuthError(Exception):
    pass


def onvif_call(ip, port, body, user, password, timeout=4, time_offset=dt.timedelta(0)):
    """Gửi lệnh ONVIF; tự thử WS-Security, rồi HTTP Digest/Basic nếu camera yêu cầu."""
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


def query_camera(ip, port, user, password, timeout=4):
    info = {}
    offset = camera_time_offset(ip, port, timeout)
    x = onvif_call(ip, port, '<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>',
                   user, password, timeout, offset)
    info["Hãng"] = tag(x, "Manufacturer")
    info["Model"] = tag(x, "Model")
    info["Firmware"] = tag(x, "FirmwareVersion")
    info["Serial"] = tag(x, "SerialNumber")
    info["Hardware ID"] = tag(x, "HardwareId")
    try:
        n = onvif_call(ip, port, '<GetNetworkInterfaces xmlns="http://www.onvif.org/ver10/device/wsdl"/>',
                       user, password, timeout, offset)
        info["MAC"] = norm_mac(tag(n, "HwAddress"))
    except Exception:
        pass
    return info


def scan_one(ip, ports, user, password, timeout):
    """Trả về dict kết quả, hoặc None nếu không có thiết bị."""
    alive = ping(ip, int(timeout * 300))
    op = open_ports(ip, ports, timeout=min(1.0, timeout / 3))
    if not alive and not op:
        return None
    row = {"IP": ip, "Trạng thái": "Có thiết bị, không thấy ONVIF"}
    for p in op:
        try:
            info = query_camera(ip, p, user, password, timeout)
            row.update({k: v for k, v in info.items() if v})
            row["Cổng ONVIF"] = p
            row["Trạng thái"] = "OK" if info.get("Serial") else "OK (camera không trả serial)"
            return row
        except AuthError:
            row["Cổng ONVIF"] = p
            row["Trạng thái"] = "Sai user/mật khẩu (hoặc lệch giờ camera)"
            return row
        except Exception:
            continue
    return row


def scan(ips, ports, user, password, timeout=4.0, workers=64, on_result=None, on_progress=None, stop=None):
    results = []
    done = 0
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(scan_one, ip, ports, user, password, timeout): ip for ip in ips}
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
    # Bổ sung MAC từ bảng ARP cho thiết bị cùng lớp mạng
    arp = arp_table()
    for r in results:
        if not r.get("MAC") and r["IP"] in arp:
            r["MAC"] = arp[r["IP"]]
    results.sort(key=lambda r: int(ipaddress.ip_address(r["IP"])))
    return results


# ---------------------------------------------------------------- xuất Excel (.xlsx, không cần thư viện)
def write_xlsx(path, rows, title="Danh sách camera"):
    def cell(ref, v, s=0):
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            return f'<c r="{ref}" s="{s}"><v>{v}</v></c>'
        return f'<c r="{ref}" s="{s}" t="inlineStr"><is><t xml:space="preserve">{escape(str(v))}</t></is></c>'

    def col(i):
        s = ""
        i += 1
        while i:
            i, r = divmod(i - 1, 26)
            s = chr(65 + r) + s
        return s

    widths = [6, 16, 20, 14, 22, 26, 26, 16, 11, 34]
    data = [COLUMNS] + [[i + 1] + [r.get(c, "") for c in COLUMNS[1:]] for i, r in enumerate(rows)]
    sheet = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>']
    sheet += [f'<col min="{i + 1}" max="{i + 1}" width="{w}" customWidth="1"/>' for i, w in enumerate(widths)]
    sheet.append("</cols><sheetData>")
    for ri, row in enumerate(data):
        sheet.append(f'<row r="{ri + 1}">' + "".join(cell(f"{col(ci)}{ri + 1}", v, 1 if ri == 0 else 0)
                                                    for ci, v in enumerate(row)) + "</row>")
    sheet.append(f'</sheetData><autoFilter ref="A1:{col(len(COLUMNS) - 1)}{len(data)}"/></worksheet>')
    styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
              '<fonts count="2"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts>'
              '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
              '<fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill></fills>'
              '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
              '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
              '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
              '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>'
              '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
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
                   f'<sheet name="{escape(title[:31])}" sheetId="1" r:id="rId1"/></sheets>'
                   f'<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">\'{escape(title[:31])}\'!$A$1:${col(len(COLUMNS) - 1)}${len(data)}</definedName></definedNames></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
                   '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
        z.writestr("xl/styles.xml", styles)
        z.writestr("xl/worksheets/sheet1.xml", "".join(sheet))


# ---------------------------------------------------------------- giao diện
def run_gui():
    import tkinter as tk
    from tkinter import ttk, messagebox, filedialog

    root = tk.Tk()
    root.title("Quét camera – IP, MAC, Serial (ONVIF)")
    root.geometry("1180x620")
    try:
        root.tk.call("tk", "scaling", 1.3)
    except Exception:
        pass

    frm = ttk.Frame(root, padding=10)
    frm.pack(fill="x")
    v_range = tk.StringVar(value="192.168.1.1-254")
    v_user = tk.StringVar(value="admin")
    v_pass = tk.StringVar()
    v_ports = tk.StringVar(value=DEFAULT_PORTS)
    v_status = tk.StringVar(value="Nhập dải IP, user/mật khẩu camera rồi bấm Quét.")

    ttk.Label(frm, text="Dải IP:").grid(row=0, column=0, sticky="w")
    ttk.Entry(frm, textvariable=v_range, width=46).grid(row=0, column=1, sticky="w", padx=4)
    ttk.Label(frm, text="User:").grid(row=0, column=2, sticky="e")
    ttk.Entry(frm, textvariable=v_user, width=14).grid(row=0, column=3, padx=4)
    ttk.Label(frm, text="Mật khẩu:").grid(row=0, column=4, sticky="e")
    ttk.Entry(frm, textvariable=v_pass, width=16, show="•").grid(row=0, column=5, padx=4)
    ttk.Label(frm, text="Cổng ONVIF:").grid(row=1, column=0, sticky="w", pady=(6, 0))
    ttk.Entry(frm, textvariable=v_ports, width=46).grid(row=1, column=1, sticky="w", padx=4, pady=(6, 0))
    ttk.Label(frm, text="VD dải IP: 192.168.1.1-254, 10.0.0.0/24, 192.168.1.10-192.168.2.50",
              foreground="#666").grid(row=1, column=2, columnspan=4, sticky="w", pady=(6, 0))
    btn_scan = ttk.Button(frm, text="▶ Quét")
    btn_stop = ttk.Button(frm, text="■ Dừng", state="disabled")
    btn_save = ttk.Button(frm, text="💾 Xuất Excel", state="disabled")
    btn_scan.grid(row=0, column=6, padx=(12, 4))
    btn_stop.grid(row=0, column=7, padx=4)
    btn_save.grid(row=1, column=6, columnspan=2, pady=(6, 0), sticky="ew")

    table = ttk.Frame(root, padding=(10, 0))
    table.pack(side="top", fill="both", expand=True)
    tv = ttk.Treeview(table, columns=COLUMNS, show="headings")
    for c, w in zip(COLUMNS, [45, 110, 140, 100, 150, 190, 190, 110, 80, 240]):
        tv.heading(c, text=c)
        tv.column(c, width=w, anchor="w")
    sb = ttk.Scrollbar(table, orient="vertical", command=tv.yview)
    tv.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    tv.pack(side="left", fill="both", expand=True)
    bar = ttk.Progressbar(root, mode="determinate")
    bar.pack(fill="x", padx=10, pady=(6, 0))
    ttk.Label(root, textvariable=v_status, padding=(10, 4)).pack(fill="x")

    q = queue.Queue()
    state = {"results": [], "stop": None}

    def refresh(rows):
        tv.delete(*tv.get_children())
        for i, r in enumerate(rows):
            tv.insert("", "end", values=[i + 1] + [r.get(c, "") for c in COLUMNS[1:]])

    def poll():
        try:
            while True:
                kind, a, b = q.get_nowait()
                if kind == "row":
                    tv.insert("", "end", values=[len(tv.get_children()) + 1] + [a.get(c, "") for c in COLUMNS[1:]])
                elif kind == "prog":
                    bar["maximum"] = b
                    bar["value"] = a
                    v_status.set(f"Đang quét {a}/{b} địa chỉ… tìm thấy {len(tv.get_children())} thiết bị")
                elif kind == "done":
                    state["results"] = a
                    refresh(a)
                    ok = sum(1 for r in a if r.get("Serial"))
                    v_status.set(f"Xong: {len(a)} thiết bị trả lời, {ok} camera lấy được serial.")
                    btn_scan["state"] = "normal"
                    btn_stop["state"] = "disabled"
                    btn_save["state"] = "normal" if a else "disabled"
                elif kind == "error":
                    messagebox.showerror("Lỗi", a)
                    btn_scan["state"] = "normal"
                    btn_stop["state"] = "disabled"
        except queue.Empty:
            pass
        root.after(150, poll)

    def start():
        try:
            ips = parse_ranges(v_range.get())
            ports = [int(p) for p in re.split(r"[,;\s]+", v_ports.get().strip()) if p]
        except Exception as e:
            messagebox.showerror("Dải IP / cổng không hợp lệ", str(e))
            return
        if len(ips) > 4096 and not messagebox.askyesno("Dải IP lớn", f"Sẽ quét {len(ips)} địa chỉ, có thể mất nhiều phút. Tiếp tục?"):
            return
        tv.delete(*tv.get_children())
        btn_scan["state"] = "disabled"
        btn_stop["state"] = "normal"
        btn_save["state"] = "disabled"
        stop = threading.Event()
        state["stop"] = stop

        def work():
            try:
                res = scan(ips, ports, v_user.get().strip(), v_pass.get(),
                           on_result=lambda r: q.put(("row", r, None)),
                           on_progress=lambda d, t: q.put(("prog", d, t)), stop=stop)
                q.put(("done", res, None))
            except Exception as e:
                q.put(("error", str(e), None))

        threading.Thread(target=work, daemon=True).start()

    def stop_scan():
        if state["stop"]:
            state["stop"].set()
            v_status.set("Đang dừng…")

    def save():
        name = "DanhSachCamera_" + dt.datetime.now().strftime("%Y%m%d_%H%M") + ".xlsx"
        path = filedialog.asksaveasfilename(defaultextension=".xlsx", initialfile=name,
                                            filetypes=[("Excel", "*.xlsx")])
        if path:
            write_xlsx(path, state["results"])
            v_status.set("Đã lưu: " + path)
            try:
                os.startfile(path)  # mở bằng Excel (Windows)
            except Exception:
                pass

    btn_scan["command"] = start
    btn_stop["command"] = stop_scan
    btn_save["command"] = save
    root.after(150, poll)
    root.mainloop()


def main():
    # Bản .exe không có cửa sổ dòng lệnh: chuyển print sang "hư không" để khỏi lỗi
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
    ap = argparse.ArgumentParser(description="Quét camera ONVIF: IP, MAC, serial")
    ap.add_argument("--range", help="Dải IP, vd 192.168.1.1-254")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="")
    ap.add_argument("--ports", default=DEFAULT_PORTS)
    ap.add_argument("--timeout", type=float, default=4.0)
    ap.add_argument("--out", help="File Excel kết quả (.xlsx)")
    a = ap.parse_args()
    if not a.range:
        run_gui()
        return
    ips = parse_ranges(a.range)
    ports = [int(p) for p in re.split(r"[,;\s]+", a.ports.strip()) if p]
    print(f"Quét {len(ips)} địa chỉ, cổng {ports} …")
    res = scan(ips, ports, a.user, a.password, a.timeout,
               on_progress=lambda d, t: print(f"\r  {d}/{t}", end="", flush=True))
    print()
    for r in res:
        print(" | ".join(str(r.get(c, "")) for c in COLUMNS[1:]))
    if a.out:
        write_xlsx(a.out, res)
        print("Đã lưu", a.out)


if __name__ == "__main__":
    main()

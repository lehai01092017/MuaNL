"""Tạo dữ liệu danh mục kèm app từ file Excel 'Danh muc hang hoa - Mua NL.xlsx'.

Cách dùng:  python3 tools/build_catalog.py "Danh muc hang hoa - Mua NL.xlsx"
Ghi ra app/src/main/assets/catalog/: index.json + ảnh JPEG đã thu nhỏ (600px).
Hỗ trợ ảnh "đặt trong ô" (Excel 365) và ảnh "đặt trên ô".
"""
import io, json, os, re, sys, zipfile, posixpath
import openpyxl
from PIL import Image

src = sys.argv[1]
out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "catalog")
z = zipfile.ZipFile(src)
rd = lambda n: z.read(n).decode("utf-8")

def resolve(base, target):
    if target.startswith("/"): return target[1:]
    return posixpath.normpath(posixpath.join(posixpath.dirname(base), target))

def rels(path):
    p = posixpath.join(posixpath.dirname(path), "_rels", posixpath.basename(path) + ".rels")
    if p not in z.namelist(): return {}
    x = rd(p); out = {}
    for m in re.finditer(r"<Relationship\b([^>]*)/?>", x):
        a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        out[a["Id"]] = a["Target"]
    return out

sheet_path = "xl/worksheets/sheet1.xml"
images = {}  # số dòng Excel (1-based) -> bytes
# Ảnh trong ô
sheet = rd(sheet_path)
if "xl/richData/rdrichvalue.xml" in z.namelist():
    rv = []
    for m in re.finditer(r"<rv\b[^>]*>(.*?)</rv>", rd("xl/richData/rdrichvalue.xml"), re.S):
        v = re.search(r"<v[^>]*>(.*?)</v>", m.group(1)); rv.append(int(v.group(1)) if v else -1)
    relids = re.findall(r'<rel [^>]*r:id="([^"]+)"', rd("xl/richData/richValueRel.xml"))
    tg = rels("xl/richData/richValueRel.xml")
    for m in re.finditer(r'<row [^>]*r="(\d+)"[^>]*>(.*?)</row>', sheet, re.S):
        v = re.search(r'<c [^>]*vm="(\d+)"', m.group(2))
        if v:
            images[int(m.group(1))] = z.read(resolve("xl/richData/richValueRel.xml", tg[relids[rv[int(v.group(1)) - 1]]]))
# Ảnh trên ô
for rid, t in rels(sheet_path).items():
    if "drawings/" not in t: continue
    dp = resolve(sheet_path, t); drels = rels(dp); d = rd(dp)
    for a in re.finditer(r"<xdr:(?:one|two)CellAnchor.*?</xdr:(?:one|two)CellAnchor>", d, re.S):
        row = re.search(r"<xdr:from>.*?<xdr:row>(\d+)</xdr:row>", a.group(0), re.S)
        emb = re.search(r'r:embed="([^"]+)"', a.group(0))
        if row and emb: images.setdefault(int(row.group(1)) + 1, z.read(resolve(dp, drels[emb.group(1)])))

ws = openpyxl.load_workbook(src, data_only=True).worksheets[0]
hdr = [str(c.value or "").strip().lower() for c in ws[1]]
col = lambda *keys: next(i for i, h in enumerate(hdr) if any(h.startswith(k) for k in keys))
c_cat, c_name, c_unit, c_price = col("danh mục"), col("hàng hóa", "hàng hoá"), col("đơn vị"), col("đơn giá")

os.makedirs(out_dir, exist_ok=True)
for f in os.listdir(out_dir): os.remove(os.path.join(out_dir, f))
items = []
for r in range(2, ws.max_row + 1):
    name = ws.cell(r, c_name + 1).value
    if not name or not str(name).strip(): continue
    e = {"category": str(ws.cell(r, c_cat + 1).value or "").strip(), "name": " ".join(str(name).split()),
         "unit": str(ws.cell(r, c_unit + 1).value or "").strip(), "price": int(round(float(ws.cell(r, c_price + 1).value or 0)))}
    if r in images:
        im = Image.open(io.BytesIO(images[r]))
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA"); bg = Image.new("RGB", im.size, "white"); bg.paste(im, mask=im.split()[-1]); im = bg
        else: im = im.convert("RGB")
        im.thumbnail((600, 600)); fn = f"{len(items) + 1:02d}.jpg"
        im.save(os.path.join(out_dir, fn), "JPEG", quality=82); e["photo"] = fn
    items.append(e)
json.dump({"version": 2, "items": items}, open(os.path.join(out_dir, "index.json"), "w"), ensure_ascii=False, indent=1)
print(f"{len(items)} mặt hàng, {sum('photo' in e for e in items)} ảnh")
for e in items: print(" ", e["category"], "|", e["name"], "|", e["unit"], "|", e["price"], "|", e.get("photo"))

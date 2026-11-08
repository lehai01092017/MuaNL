package vn.nguyenlieu.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

data class CatalogRow(
    val name: String,
    val unit: String,
    val price: Long,
    val category: String?,
    /** Ảnh sản phẩm chèn trong Excel (đã thu nhỏ, JPEG), null nếu dòng không có ảnh. */
    val photo: ByteArray? = null,
)

/** Kết quả đọc trang tính: các dòng (số dòng 0-based -> ô) và ô ảnh-trong-ô (số dòng -> chỉ số vm). */
private class SheetCells(val rows: List<Pair<Int, Map<Int, String>>>, val vm: Map<Int, Int>)

data class CatalogResult(val added: Int, val updated: Int, val newCategories: Int)

/**
 * Đọc danh mục hàng hoá từ file Excel (.xlsx) — không cần thư viện ngoài.
 * Tự tìm dòng tiêu đề có cột "Hàng hóa/Tên" và "Đơn giá"; cột "Đơn vị", "Danh mục/Nhóm/Loại" là tuỳ chọn.
 */
object ExcelImport {
    const val DEFAULT_CATEGORY = "Chưa phân loại"

    fun read(ctx: Context, uri: Uri): List<CatalogRow> {
        val files = HashMap<String, ByteArray>()
        val input = requireNotNull(ctx.contentResolver.openInputStream(uri)) { "Không đọc được file" }
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.startsWith("xl/")) files[e.name] = zip.readBytes()
            }
        }
        require(files.isNotEmpty()) { "File không phải Excel .xlsx (nếu là .xls hãy lưu lại thành .xlsx)" }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val sheetPath = firstSheetPath(files)
        val sheet = files[sheetPath] ?: throw IllegalArgumentException("Không tìm thấy trang tính")
        val parsed = cells(sheet, shared)
        val images = HashMap<Int, ByteArray>()
        try { images.putAll(anchoredImages(files, sheetPath)) } catch (_: Exception) {}
        try { inCellImages(files, parsed.vm).forEach { (r, b) -> images.putIfAbsent(r, b) } } catch (_: Exception) {}
        return toRows(parsed.rows, images)
    }

    // ---------- Ảnh trong Excel ----------
    /** "xl/worksheets/sheet1.xml" -> "xl/worksheets/_rels/sheet1.xml.rels" */
    private fun relsPathOf(part: String) =
        part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"

    /** Ghép đường dẫn tương đối ("../media/image1.png") theo file gốc. */
    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = base.split("/").dropLast(1).toMutableList()
        for (seg in target.split("/")) when (seg) {
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            ".", "" -> {}
            else -> parts += seg
        }
        return parts.joinToString("/")
    }

    private fun rels(files: Map<String, ByteArray>, path: String): Map<String, String> {
        val out = HashMap<String, String>()
        val bytes = files[path] ?: return out
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                out[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target")
            }
        }
        return out
    }

    private fun attrEndingWith(p: XmlPullParser, suffix: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == suffix || n.endsWith(":$suffix")) return p.getAttributeValue(i)
        }
        return null
    }

    /** Ảnh "đặt trên ô" (Insert > Picture): lấy theo dòng của góc trên-trái ảnh. */
    private fun anchoredImages(files: Map<String, ByteArray>, sheetPath: String): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        for (target in rels(files, relsPathOf(sheetPath)).values) {
            if (!target.contains("drawings/")) continue
            val drawPath = resolve(sheetPath, target)
            val bytes = files[drawPath] ?: continue
            val drawRels = rels(files, relsPathOf(drawPath))
            val p = parser(bytes)
            var row = -1; var embed: String? = null
            var inFrom = false; var inRow = false
            val sb = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                val local = p.name?.substringAfter(':')
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (local) {
                        "twoCellAnchor", "oneCellAnchor", "absoluteAnchor" -> { row = -1; embed = null }
                        "from" -> inFrom = true
                        "row" -> if (inFrom) { inRow = true; sb.setLength(0) }
                        "blip" -> if (embed == null) embed = attrEndingWith(p, "embed")
                    }
                    XmlPullParser.TEXT -> if (inRow) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (local) {
                        "row" -> if (inRow) { inRow = false; row = sb.toString().trim().toIntOrNull() ?: -1 }
                        "from" -> inFrom = false
                        "twoCellAnchor", "oneCellAnchor", "absoluteAnchor" -> {
                            val img = embed?.let { drawRels[it] }?.let { files[resolve(drawPath, it)] }
                            if (row >= 0 && img != null) out.putIfAbsent(row, img)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Ảnh "đặt trong ô" (Excel 365: Place in Cell): ô có thuộc tính vm trỏ tới richData. */
    private fun inCellImages(files: Map<String, ByteArray>, vm: Map<Int, Int>): Map<Int, ByteArray> {
        if (vm.isEmpty()) return emptyMap()
        val rvBytes = files["xl/richData/rdrichvalue.xml"] ?: return emptyMap()
        // Mỗi <rv> có <v> đầu tiên = chỉ số trong richValueRel
        val rvRel = ArrayList<Int>()
        run {
            val p = parser(rvBytes)
            var inRv = false; var first = true; var inV = false
            val sb = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "rv" -> { inRv = true; first = true }
                        "v" -> if (inRv && first) { inV = true; sb.setLength(0) }
                    }
                    XmlPullParser.TEXT -> if (inV) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v" -> if (inV) { inV = false; first = false; rvRel += sb.toString().trim().toIntOrNull() ?: -1 }
                        "rv" -> { if (first) rvRel += -1; inRv = false }
                    }
                }
            }
        }
        val relPath = "xl/richData/richValueRel.xml"
        val relIds = ArrayList<String>()
        files[relPath]?.let { bytes ->
            val p = parser(bytes)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rel") {
                    relIds += attrEndingWith(p, "id") ?: ""
                }
            }
        } ?: return emptyMap()
        val relTargets = rels(files, relsPathOf(relPath))
        val out = HashMap<Int, ByteArray>()
        for ((row, v) in vm) {
            val relIdx = rvRel.getOrNull(v - 1) ?: continue
            val target = relTargets[relIds.getOrNull(relIdx) ?: continue] ?: continue
            files[resolve(relPath, target)]?.let { out[row] = it }
        }
        return out
    }

    // ---------- Phân tích XML ----------
    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(bytes)
        val sb = StringBuilder()
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb.setLength(0)
                    "t" -> inT = true
                    "rPh" -> skip(p) // phiên âm (tiếng Nhật) — bỏ qua
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> out += sb.toString()
                }
            }
        }
        return out
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    /** Trang tính đầu tiên theo thứ tự trong workbook. */
    private fun firstSheetPath(files: Map<String, ByteArray>): String {
        try {
            var rid: String? = null
            files["xl/workbook.xml"]?.let { bytes ->
                val p = parser(bytes)
                while (p.next() != XmlPullParser.END_DOCUMENT && rid == null) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") rid = p.getAttributeValue(null, "r:id")
                }
            }
            files["xl/_rels/workbook.xml.rels"]?.let { bytes ->
                val p = parser(bytes)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" &&
                        p.getAttributeValue(null, "Id") == rid
                    ) {
                        val t = p.getAttributeValue(null, "Target").removePrefix("/")
                        return if (t.startsWith("xl/")) t else "xl/$t"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull { it.length * 1000 + it.hashCode() % 1000 }
            ?: "xl/worksheets/sheet1.xml"
    }

    /** Trả về danh sách dòng, mỗi dòng là map cột(0-based) -> giá trị chuỗi. */
    private fun cells(bytes: ByteArray, shared: List<String>): SheetCells {
        val rows = ArrayList<Pair<Int, Map<Int, String>>>()
        val vms = HashMap<Int, Int>()
        var rowIdx = -1
        val p = parser(bytes)
        var row: HashMap<Int, String>? = null
        var col = 0; var type = ""; var nextCol = 0
        val sb = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        row = HashMap(); nextCol = 0
                        rowIdx = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (rowIdx + 1)
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t") ?: ""
                        p.getAttributeValue(null, "vm")?.toIntOrNull()?.let { vms.putIfAbsent(rowIdx, it) }
                        sb.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = sb.toString()
                        val value = if (type == "s") raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: "" else raw
                        if (value.isNotBlank()) row?.put(col, value.trim())
                    }
                    "row" -> row?.let { rows += rowIdx to it }
                }
            }
        }
        return SheetCells(rows, vms)
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    // ---------- Nhận dạng cột ----------
    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd').trim()

    private fun toRows(rows: List<Pair<Int, Map<Int, String>>>, images: Map<Int, ByteArray>): List<CatalogRow> {
        var nameCol = -1; var unitCol = -1; var priceCol = -1; var catCol = -1
        var start = -1
        for ((i, pair) in rows.withIndex()) {
            val r = pair.second
            var n = -1; var u = -1; var pr = -1; var c = -1
            for ((col, v) in r) {
                val t = norm(v)
                when {
                    n < 0 && (t.startsWith("hang hoa") || t.startsWith("ten") || t.contains("san pham") ||
                        t.contains("nguyen lieu") || t == "mat hang") -> n = col
                    u < 0 && (t.startsWith("don vi") || t == "dvt" || t == "dv") -> u = col
                    pr < 0 && (t.startsWith("don gia") || t == "gia" || t.startsWith("gia ")) -> pr = col
                    c < 0 && (t.startsWith("danh muc") || t.startsWith("nhom") || t.startsWith("loai")) -> c = col
                }
            }
            if (n >= 0 && pr >= 0) { nameCol = n; unitCol = u; priceCol = pr; catCol = c; start = i + 1; break }
        }
        require(start >= 0) { "Không thấy dòng tiêu đề có cột \"Hàng hóa\" và \"Đơn giá\"" }

        val out = ArrayList<CatalogRow>()
        for ((rowIdx, r) in rows.drop(start)) {
            val name = r[nameCol]?.trim().orEmpty()
            if (name.isEmpty()) continue
            val n = norm(name)
            if (n.startsWith("tong") || n.startsWith("cong")) continue // dòng tổng cộng
            out += CatalogRow(
                name = name,
                unit = if (unitCol >= 0) r[unitCol].orEmpty() else "",
                price = parsePrice(r[priceCol].orEmpty()),
                category = if (catCol >= 0) r[catCol]?.takeIf { it.isNotBlank() } else null,
                photo = images[rowIdx]?.let { Photos.fromBytes(it) },
            )
        }
        require(out.isNotEmpty()) { "Không có mặt hàng nào bên dưới dòng tiêu đề" }
        return out
    }

    private fun parsePrice(s: String): Long {
        s.toDoubleOrNull()?.let { return it.roundToLong() } // ô số: "369000" hoặc "3.69E5"
        return Fmt.parseMoney(s)                              // ô chữ: "369.000đ"
    }
}

/** Ghi danh mục vào CSDL: trùng tên thì cập nhật đơn vị, giá (và danh mục nếu file có cột danh mục). */
fun Db.importCatalog(rows: List<CatalogRow>): CatalogResult {
    val db = writableDatabase
    var added = 0; var updated = 0; var newCats = 0
    val catIds = HashMap<String, Long>()
    fun categoryId(name: String): Long = catIds.getOrPut(name.lowercase()) {
        db.rawQuery("SELECT id FROM categories WHERE name=? COLLATE NOCASE", arrayOf(name))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: run {
                val sort = db.rawQuery("SELECT COALESCE(MAX(sort),0)+1 FROM categories", null)
                    .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                newCats++
                db.insert("categories", null, ContentValues().apply { put("name", name); put("sort", sort) })
            }
    }
    db.beginTransaction()
    try {
        for (r in rows) {
            val existing = db.rawQuery("SELECT id FROM items WHERE name=? COLLATE NOCASE", arrayOf(r.name))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            val v = ContentValues().apply {
                put("name", r.name); put("unit", r.unit); put("price", r.price)
                if (r.photo != null) { put("photo", r.photo); put("photo_ver", System.currentTimeMillis()) }
            }
            if (existing != null) {
                if (r.category != null) v.put("category_id", categoryId(r.category))
                db.update("items", v, "id=?", arrayOf(existing.toString())); updated++
            } else {
                v.put("category_id", categoryId(r.category ?: ExcelImport.DEFAULT_CATEGORY))
                db.insert("items", null, v); added++
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    return CatalogResult(added, updated, newCats)
}

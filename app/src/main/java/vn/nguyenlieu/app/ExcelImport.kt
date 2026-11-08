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

data class CatalogRow(val name: String, val unit: String, val price: Long, val category: String?)

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
        return toRows(cells(sheet, shared))
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
    private fun cells(bytes: ByteArray, shared: List<String>): List<Map<Int, String>> {
        val rows = ArrayList<Map<Int, String>>()
        val p = parser(bytes)
        var row: HashMap<Int, String>? = null
        var col = 0; var type = ""; var nextCol = 0
        val sb = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { row = HashMap(); nextCol = 0 }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t") ?: ""
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
                    "row" -> row?.let { rows += it }
                }
            }
        }
        return rows
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

    private fun toRows(rows: List<Map<Int, String>>): List<CatalogRow> {
        var nameCol = -1; var unitCol = -1; var priceCol = -1; var catCol = -1
        var start = -1
        for ((i, r) in rows.withIndex()) {
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
        for (r in rows.drop(start)) {
            val name = r[nameCol]?.trim().orEmpty()
            if (name.isEmpty()) continue
            val n = norm(name)
            if (n.startsWith("tong") || n.startsWith("cong")) continue // dòng tổng cộng
            out += CatalogRow(
                name = name,
                unit = if (unitCol >= 0) r[unitCol].orEmpty() else "",
                price = parsePrice(r[priceCol].orEmpty()),
                category = if (catCol >= 0) r[catCol]?.takeIf { it.isNotBlank() } else null,
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

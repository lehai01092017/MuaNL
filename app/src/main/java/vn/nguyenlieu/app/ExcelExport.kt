package vn.nguyenlieu.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Xuất dữ liệu ra Excel (.xlsx) — tự ghi định dạng, không cần thư viện ngoài.
 *  - Trang 1 "Danh mục hàng hoá": STT, Danh mục, Hàng hóa, Đơn vị, Đơn giá (nhập ngược lại vào app được)
 *  - Trang 2 "Lịch sử bill": mỗi dòng một mặt hàng trong bill
 *  - Trang 3 "Tổng hợp bill": mỗi dòng một bill
 */
object ExcelExport {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Ô: String hoặc Number; style 1 = tiêu đề đậm, 2 = số có dấu chấm nghìn. */
    private class Sheet(
        val name: String,
        val widths: List<Int>,
        val header: List<String>,
        val rows: List<List<Any?>>,
        /** Ảnh theo chỉ số dòng dữ liệu (0 = dòng đầu sau tiêu đề), đặt ở cột imageCol. */
        val images: Map<Int, ByteArray> = emptyMap(),
        val imageCol: Int = -1,
    )

    private const val IMG_PX = 80          // cỡ ảnh trong Excel (điểm ảnh)
    private const val EMU_PER_PX = 9525L

    fun export(ctx: Context) {
        val db = Db.get(ctx)
        val cats = db.categories().associateBy { it.id }
        val items = db.items().sortedWith(compareBy({ cats[it.categoryId]?.name ?: "" }, { it.name.lowercase() }))
        val photos = HashMap<Int, ByteArray>()
        items.forEachIndexed { i, it -> if (it.photoVer != 0L) db.photo(it.id)?.let { b -> photos[i] = b } }
        val catalog = Sheet(
            "Danh mục hàng hoá", listOf(6, 18, 34, 10, 12, 13),
            listOf("STT", "Danh mục", "Hàng hóa", "Đơn vị", "Đơn giá", "Ảnh"),
            items.mapIndexed { i, it -> listOf(i + 1, cats[it.categoryId]?.name ?: "", it.name, it.unit, it.price) },
            images = photos, imageCol = 5,
        )
        val bills = db.bills().sortedBy { it.createdAt }
        val lineRows = ArrayList<List<Any?>>()
        for (b in bills) for (l in db.billLines(b.id)) {
            lineRows += listOf(b.id, Fmt.dateTime(b.createdAt), b.creator, b.note,
                l.categoryName, l.itemName, l.unit, l.qty, l.price, l.amount)
        }
        val history = Sheet(
            "Lịch sử bill", listOf(8, 18, 16, 20, 16, 30, 9, 8, 12, 14),
            listOf("Số bill", "Ngày giờ", "Người tạo", "Ghi chú", "Danh mục", "Hàng hóa", "Đơn vị", "SL", "Đơn giá", "Thành tiền"),
            lineRows
        )
        val summary = Sheet(
            "Tổng hợp bill", listOf(8, 18, 16, 24, 8, 14),
            listOf("Số bill", "Ngày giờ", "Người tạo", "Ghi chú", "Số món", "Tổng tiền"),
            bills.map { listOf(it.id, Fmt.dateTime(it.createdAt), it.creator, it.note, it.lineCount, it.total) }
        )

        val dir = File(ctx.cacheDir, "bills").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val file = File(dir, "MuaNL_DuLieu_$stamp.xlsx")
        write(file, listOf(catalog, history, summary))

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("excel", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Gửi file Excel"))
    }

    /** Kích thước (rộng, cao) của ảnh JPEG/PNG. */
    private fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0) o.outWidth to o.outHeight else 1 to 1
    }

    // ---------- Ghi file xlsx ----------
    private fun esc(s: String) = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c < ' ' && c != '\n' && c != '\t' -> {}
            else -> append(c)
        }
    }

    private fun colName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun cell(ref: String, v: Any?, style: Int): String = when (v) {
        null -> ""
        is Number -> {
            val d = v.toDouble()
            val txt = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
            val st = if (style == 1) 1 else if (Math.abs(d) >= 1000) 2 else 0
            "<c r=\"$ref\" s=\"$st\"><v>$txt</v></c>"
        }
        else -> "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>"
    }

    private fun sheetXml(s: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        val all = listOf(s.header) + s.rows
        all.forEachIndexed { r, row ->
            // Dòng có ảnh: tăng chiều cao cho vừa ảnh (pt = px * 0.75)
            if (r > 0 && s.images.containsKey(r - 1)) append("<row r=\"${r + 1}\" ht=\"${IMG_PX * 3 / 4 + 4}\" customHeight=\"1\">")
            else append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v -> append(cell("${colName(c)}${r + 1}", v, if (r == 0) 1 else 0)) }
            append("</row>")
        }
        append("</sheetData>")
        if (s.images.isNotEmpty()) append("""<drawing r:id="rId1"/>""")
        append("</worksheet>")
    }

    private fun write(file: File, sheets: List<Sheet>) {
        ZipOutputStream(file.outputStream()).use { z ->
            fun putBytes(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            fun put(name: String, content: String) = putBytes(name, content.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach {
                    append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                    if (sheets[it].images.isNotEmpty())
                        append("""<Override PartName="/xl/drawings/drawing${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
                }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach {
                    append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF2E7D32"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }

            // Ảnh: mỗi trang có ảnh -> 1 drawing, mỗi ảnh neo vào ô (cột ảnh, dòng của hàng hoá)
            var mediaNo = 0
            sheets.forEachIndexed { i, s ->
                if (s.images.isEmpty()) return@forEachIndexed
                val n = i + 1
                put("xl/worksheets/_rels/sheet$n.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing$n.xml"/></Relationships>""")
                val drawing = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
                val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                var k = 0
                for ((dataRow, bytes) in s.images.toSortedMap()) {
                    k++; mediaNo++
                    putBytes("xl/media/image$mediaNo.jpg", bytes)
                    val (w, h) = imageSize(bytes)
                    val scale = IMG_PX.toDouble() / maxOf(w, h, 1)
                    val cx = (w * scale * EMU_PER_PX).toLong(); val cy = (h * scale * EMU_PER_PX).toLong()
                    val row = dataRow + 1 // +1 vì dòng 0 là tiêu đề
                    drawing.append("""<xdr:oneCellAnchor><xdr:from><xdr:col>${s.imageCol}</xdr:col><xdr:colOff>19050</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>19050</xdr:rowOff></xdr:from><xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${k + 1}" name="Anh $k"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId$k"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""")
                    rels.append("""<Relationship Id="rId$k" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image$mediaNo.jpg"/>""")
                }
                drawing.append("</xdr:wsDr>"); rels.append("</Relationships>")
                put("xl/drawings/drawing$n.xml", drawing.toString())
                put("xl/drawings/_rels/drawing$n.xml.rels", rels.toString())
            }
        }
    }
}

package vn.nguyenlieu.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object BillShare {
    const val ZALO = "com.zing.zalo"

    fun text(bill: Bill, lines: List<BillLine>): String = buildString {
        appendLine("🧾 PHIẾU MUA NGUYÊN LIỆU #${bill.id}")
        appendLine("🕒 ${Fmt.dateTime(bill.createdAt)}")
        if (bill.creator.isNotBlank()) appendLine("👤 Người tạo: ${bill.creator}")
        if (bill.note.isNotBlank()) appendLine("📝 ${bill.note}")
        appendLine("------------------------------")
        lines.forEachIndexed { i, l ->
            appendLine("${i + 1}. ${l.itemName}")
            appendLine("   ${Fmt.qty(l.qty)} ${l.unit} x ${Fmt.number(l.price)} = ${Fmt.money(l.amount)}")
        }
        appendLine("------------------------------")
        appendLine("Số món: ${lines.size}")
        append("TỔNG CỘNG: ${Fmt.money(bill.total)}")
    }

    /** Vẽ bill thành ảnh PNG kiểu hoá đơn. */
    fun image(bill: Bill, lines: List<BillLine>): Bitmap {
        val w = 800
        val pad = 40f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 40f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 24f }
        val bold = Paint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
        val right = Paint(normal).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(right).apply { typeface = Typeface.DEFAULT_BOLD }
        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 125, 50); textSize = 36f; typeface = Typeface.DEFAULT_BOLD
        }
        val totalRight = Paint(totalPaint).apply { textAlign = Paint.Align.RIGHT }
        val dash = Paint().apply {
            color = Color.GRAY; strokeWidth = 2f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        val nameWidth = w - pad * 2
        // Tính chiều cao trước
        val rowsHeight = lines.sumOf { wrap(it.itemName, bold, nameWidth).size * 36 + 38 }
        val noteLines = (if (bill.creator.isBlank()) emptyList() else wrap("Người tạo: " + bill.creator, small, nameWidth)) +
            (if (bill.note.isBlank()) emptyList() else wrap("Ghi chú: " + bill.note, small, nameWidth))
        val h = (pad + 60 + 40 + noteLines.size * 32 + 40 + rowsHeight + 40 + 50 + 60 + 60 + pad).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = pad + 40f
        c.drawText("PHIẾU MUA NGUYÊN LIỆU", w / 2f, y, title)
        y += 44f
        c.drawText("Số: #${bill.id}   •   ${Fmt.dateTime(bill.createdAt)}", w / 2f, y,
            Paint(small).apply { textAlign = Paint.Align.CENTER })
        y += 12f
        for (nl in noteLines) { y += 32f; c.drawText(nl, pad, y, small) }
        y += 24f
        c.drawLine(pad, y, w - pad, y, dash)
        y += 8f

        lines.forEachIndexed { i, l ->
            val nameLines = wrap("${i + 1}. ${l.itemName}", bold, nameWidth)
            for (nl in nameLines) { y += 36f; c.drawText(nl, pad, y, bold) }
            y += 34f
            c.drawText("${Fmt.qty(l.qty)} ${l.unit} × ${Fmt.number(l.price)}", pad + 30f, y, normal)
            c.drawText(Fmt.money(l.amount), w - pad, y, right)
            y += 4f
        }
        y += 24f
        c.drawLine(pad, y, w - pad, y, dash)
        y += 44f
        c.drawText("Số món", pad, y, normal)
        c.drawText(lines.size.toString(), w - pad, y, rightBold)
        y += 54f
        c.drawText("TỔNG CỘNG", pad, y, totalPaint)
        c.drawText(Fmt.money(bill.total), w - pad, y, totalRight)
        y += 50f
        c.drawText("Cảm ơn!", w / 2f, y, Paint(small).apply { textAlign = Paint.Align.CENTER })
        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) <= maxWidth || line.isEmpty()) line = test
            else { out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        return out.ifEmpty { listOf("") }
    }

    private fun saveImage(ctx: Context, bill: Bill, lines: List<BillLine>): Uri {
        val dir = File(ctx.cacheDir, "bills").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // dọn ảnh cũ
        val file = File(dir, "bill_${bill.id}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { image(bill, lines).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    /** Gửi ảnh bill + file đơn hàng (.json) cùng lúc, để máy nhận thêm được đơn. */
    fun shareImageAndFile(ctx: Context, bill: Bill, lines: List<BillLine>, pkg: String? = null) {
        val img = saveImage(ctx, bill, lines)
        val file = BillFile.write(ctx, bill, lines)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(img, file))
            clipData = ClipData.newRawUri("bill", img).apply { addItem(ClipData.Item(file)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(ctx, intent, pkg)
    }

    /** Chỉ gửi file đơn hàng. */
    fun shareBillFile(ctx: Context, bill: Bill, lines: List<BillLine>, pkg: String? = null) {
        File(ctx.cacheDir, "bills").listFiles()?.forEach { it.delete() }
        val file = BillFile.write(ctx, bill, lines)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, file)
            clipData = ClipData.newRawUri("bill", file)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(ctx, intent, pkg)
    }

    /** Chia sẻ dạng ảnh. pkg = ZALO để mở thẳng Zalo, null để chọn ứng dụng. */
    fun shareImage(ctx: Context, bill: Bill, lines: List<BillLine>, pkg: String? = null) {
        val uri = saveImage(ctx, bill, lines)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("bill", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(ctx, intent, pkg)
    }

    fun shareText(ctx: Context, bill: Bill, lines: List<BillLine>, pkg: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text(bill, lines))
        }
        launch(ctx, intent, pkg)
    }

    private fun launch(ctx: Context, intent: Intent, pkg: String?) {
        if (pkg != null) {
            try {
                ctx.startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(ctx, "Chưa cài Zalo — chọn ứng dụng khác", Toast.LENGTH_SHORT).show()
            }
        }
        ctx.startActivity(Intent.createChooser(intent, "Chia sẻ bill qua"))
    }
}

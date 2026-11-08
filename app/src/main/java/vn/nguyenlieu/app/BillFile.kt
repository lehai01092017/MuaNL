package vn.nguyenlieu.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Một đơn hàng gửi giữa các máy. */
data class SharedBill(
    val createdAt: Long,
    val note: String,
    val creator: String,
    val lines: List<BillLine>,
)

/**
 * File đơn hàng (.json) đi kèm ảnh bill. Máy nhận mở file bằng Mua NL
 * (hoặc ⚙ Cài đặt > Nhập dữ liệu từ file) để thêm đơn vào lịch sử.
 */
object BillFile {
    private const val TYPE = "muanl-bill"

    fun toJson(bill: Bill, lines: List<BillLine>): String = JSONObject().apply {
        put("type", TYPE)
        put("version", 1)
        put("createdAt", bill.createdAt)
        put("creator", bill.creator)
        put("note", bill.note)
        put("total", bill.total)
        put("lines", JSONArray().apply {
            lines.forEach { l ->
                put(JSONObject().apply {
                    put("name", l.itemName); put("category", l.categoryName)
                    put("unit", l.unit); put("price", l.price); put("qty", l.qty)
                })
            }
        })
    }.toString(2)

    fun parse(text: String): SharedBill? = try {
        val o = JSONObject(text.trim().removePrefix("﻿"))
        if (o.optString("type") != TYPE) null else {
            val arr = o.getJSONArray("lines")
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                BillLine(
                    l.getString("name"), l.optString("category"), l.optString("unit"),
                    l.optLong("price"), l.optDouble("qty", 1.0)
                )
            }
            if (lines.isEmpty()) null
            else SharedBill(o.optLong("createdAt", System.currentTimeMillis()), o.optString("note"), o.optString("creator"), lines)
        }
    } catch (e: Exception) {
        null
    }

    /** Ghi file vào cache/bills và trả về uri chia sẻ được. Gọi SAU khi đã lưu ảnh (saveImage dọn thư mục). */
    fun write(ctx: Context, bill: Bill, lines: List<BillLine>): Uri {
        val dir = File(ctx.cacheDir, "bills").apply { mkdirs() }
        val who = bill.creator.filter { it.isLetterOrDigit() }.take(20)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(bill.createdAt))
        val f = File(dir, "DonHang_${if (who.isEmpty()) "" else who + "_"}${stamp}.json")
        f.writeText(toJson(bill, lines))
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }
}

/** Thêm đơn từ máy khác; trả về id bill mới, hoặc null nếu đơn đã có (cùng thời điểm, tổng, người tạo). */
fun Db.importBill(b: SharedBill): Long? {
    val total = b.lines.sumOf { it.amount }
    val dup = readableDatabase.rawQuery(
        "SELECT 1 FROM bills WHERE created_at=? AND total=? AND creator=?",
        arrayOf(b.createdAt.toString(), total.toString(), b.creator)
    ).use { it.moveToFirst() }
    if (dup) return null
    return saveBill(b.note, b.creator, b.lines, b.createdAt)
}

package vn.nguyenlieu.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cài đặt nhỏ lưu trong SharedPreferences. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun creator(ctx: Context): String = sp(ctx).getString("creator", "") ?: ""
    fun setCreator(ctx: Context, name: String) = sp(ctx).edit().putString("creator", name.trim()).apply()
}

data class MergeResult(val categories: Int, val items: Int, val bills: Int)

/**
 * Sao lưu & chuyển dữ liệu sang máy khác:
 *  - export: chép file CSDL rồi chia sẻ (Zalo, Drive, Bluetooth, Gmail…)
 *  - import: nhận file .db, "gộp" vào dữ liệu hiện có hoặc "thay thế" toàn bộ.
 */
object Backup {

    private fun dbFile(ctx: Context) = ctx.getDatabasePath(Db.DB_NAME)

    /** Tạo bản sao CSDL trong cache và mở bảng chia sẻ. */
    fun export(ctx: Context) {
        val db = Db.get(ctx)
        // Đảm bảo mọi thay đổi đã ghi vào file chính
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val dir = File(ctx.cacheDir, "bills").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".db") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val who = Prefs.creator(ctx).filter { it.isLetterOrDigit() }.take(20)
        val out = File(dir, "MuaNL_${if (who.isEmpty()) "" else who + "_"}$stamp.db")
        dbFile(ctx).copyTo(out, overwrite = true)

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Dữ liệu Mua NL — mở app > Cài đặt > Nhập dữ liệu để dùng file này.")
            clipData = ClipData.newRawUri("backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Gửi dữ liệu sang máy khác"))
    }

    /** Chép file người dùng chọn vào cache và kiểm tra đúng là CSDL của app. Ném lỗi kèm thông báo nếu sai. */
    fun receive(ctx: Context, uri: Uri): File {
        val tmp = File(ctx.cacheDir, "import.db")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Không đọc được file" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        val header = ByteArray(16)
        tmp.inputStream().use { it.read(header) }
        require(String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) {
            "File không phải dữ liệu của ứng dụng"
        }
        SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
            val tables = HashSet<String>()
            src.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use {
                while (it.moveToNext()) tables += it.getString(0)
            }
            require(tables.containsAll(listOf("categories", "items", "bills", "bill_items"))) {
                "File không phải dữ liệu của ứng dụng"
            }
            require(src.version <= Db.DB_VERSION) { "File được tạo từ phiên bản app mới hơn — hãy cập nhật app" }
        }
        return tmp
    }

    /** Thay thế toàn bộ dữ liệu trên máy bằng file nhận được. */
    fun replace(ctx: Context, file: File) {
        Db.get(ctx).close()
        val target = dbFile(ctx)
        listOf("-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
        file.copyTo(target, overwrite = true)
        file.delete()
        Db.get(ctx).writableDatabase // mở lại (tự nâng cấp cấu trúc nếu file cũ)
    }

    /**
     * Gộp: thêm danh mục / nguyên liệu chưa có (so theo tên), thêm bill chưa có
     * (so theo thời điểm tạo + tổng tiền + người tạo). Dữ liệu sẵn có không bị sửa.
     */
    fun merge(ctx: Context, file: File): MergeResult {
        val dst = Db.get(ctx).writableDatabase
        var nCat = 0; var nItem = 0; var nBill = 0
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
            fun hasColumn(table: String, col: String) = src.rawQuery("PRAGMA table_info($table)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == col) found = true
                found
            }
            val hasCreator = hasColumn("bills", "creator")
            val hasPhoto = hasColumn("items", "photo")
            dst.beginTransaction()
            try {
                // 1. Danh mục: id nguồn -> id đích
                val catMap = HashMap<Long, Long>()
                src.rawQuery("SELECT id, name FROM categories ORDER BY sort", null).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1)
                        val existing = dst.rawQuery(
                            "SELECT id FROM categories WHERE name=? COLLATE NOCASE", arrayOf(name)
                        ).use { if (it.moveToFirst()) it.getLong(0) else null }
                        catMap[c.getLong(0)] = existing ?: run {
                            val sort = dst.rawQuery("SELECT COALESCE(MAX(sort),0)+1 FROM categories", null)
                                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                            nCat++
                            dst.insert("categories", null, ContentValues().apply {
                                put("name", name); put("sort", sort)
                            })
                        }
                    }
                }
                // 2. Nguyên liệu
                val itemSql = "SELECT category_id, name, unit, price" +
                    (if (hasPhoto) ", photo, photo_ver" else ", NULL, 0") + " FROM items"
                src.rawQuery(itemSql, null).use { c ->
                    while (c.moveToNext()) {
                        val cat = catMap[c.getLong(0)] ?: continue
                        val name = c.getString(1)
                        val exists = dst.rawQuery(
                            "SELECT 1 FROM items WHERE category_id=? AND name=? COLLATE NOCASE",
                            arrayOf(cat.toString(), name)
                        ).use { it.moveToFirst() }
                        if (!exists) {
                            dst.insert("items", null, ContentValues().apply {
                                put("category_id", cat); put("name", name)
                                put("unit", c.getString(2)); put("price", c.getLong(3))
                                if (!c.isNull(4)) { put("photo", c.getBlob(4)); put("photo_ver", c.getLong(5)) }
                            })
                            nItem++
                        }
                    }
                }
                // 3. Bill
                val billSql = "SELECT id, created_at, total, note" +
                    (if (hasCreator) ", creator" else ", ''") + " FROM bills ORDER BY created_at"
                src.rawQuery(billSql, null).use { c ->
                    while (c.moveToNext()) {
                        val createdAt = c.getLong(1); val total = c.getLong(2); val creator = c.getString(4)
                        val dup = dst.rawQuery(
                            "SELECT 1 FROM bills WHERE created_at=? AND total=? AND creator=?",
                            arrayOf(createdAt.toString(), total.toString(), creator)
                        ).use { it.moveToFirst() }
                        if (dup) continue
                        val newId = dst.insert("bills", null, ContentValues().apply {
                            put("created_at", createdAt); put("total", total)
                            put("note", c.getString(3)); put("creator", creator)
                        })
                        src.rawQuery(
                            "SELECT item_name, category_name, unit, price, qty FROM bill_items WHERE bill_id=? ORDER BY id",
                            arrayOf(c.getLong(0).toString())
                        ).use { l ->
                            while (l.moveToNext()) dst.insert("bill_items", null, ContentValues().apply {
                                put("bill_id", newId); put("item_name", l.getString(0))
                                put("category_name", l.getString(1)); put("unit", l.getString(2))
                                put("price", l.getLong(3)); put("qty", l.getDouble(4))
                            })
                        }
                        nBill++
                    }
                }
                dst.setTransactionSuccessful()
            } finally {
                dst.endTransaction()
            }
        }
        file.delete()
        return MergeResult(nCat, nItem, nBill)
    }
}

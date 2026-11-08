package vn.nguyenlieu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.roundToLong

// ---------- Mô hình dữ liệu ----------
data class Category(val id: Long, val name: String, val itemCount: Int)

data class Item(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val unit: String,
    val price: Long,
    /** 0 = chưa có ảnh; khác 0 = mốc thời gian lần đổi ảnh gần nhất (dùng làm khoá cache). */
    val photoVer: Long = 0,
)

data class Bill(
    val id: Long,
    val createdAt: Long,
    val total: Long,
    val note: String,
    val lineCount: Int,
    val creator: String = "",
)

/** Một dòng trong bill — lưu bản chụp tên/giá để thống kê không bị ảnh hưởng khi sửa, xoá nguyên liệu. */
data class BillLine(
    val itemName: String,
    val categoryName: String,
    val unit: String,
    val price: Long,
    val qty: Double,
) {
    val amount: Long get() = (price * qty).roundToLong()
}

data class StatRow(val label: String, val amount: Long, val qty: Double = 0.0, val unit: String = "")

// ---------- Cơ sở dữ liệu ----------
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "nguyenlieu.db"
        const val DB_VERSION = 5
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(ctx).also { instance = it } }
    }

    init {
        // Giữ toàn bộ dữ liệu trong 1 file để sao lưu/chép sang máy khác an toàn
        setWriteAheadLoggingEnabled(false)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE categories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                sort INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
                name TEXT NOT NULL,
                unit TEXT NOT NULL DEFAULT '',
                price INTEGER NOT NULL DEFAULT 0,
                photo BLOB,
                photo_ver INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE bills(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                total INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                creator TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE bill_items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bill_id INTEGER NOT NULL REFERENCES bills(id) ON DELETE CASCADE,
                item_name TEXT NOT NULL,
                category_name TEXT NOT NULL,
                unit TEXT NOT NULL,
                price INTEGER NOT NULL,
                qty REAL NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_items_cat ON items(category_id)")
        db.execSQL("CREATE INDEX idx_bill_items_bill ON bill_items(bill_id)")
        db.execSQL("CREATE INDEX idx_bills_time ON bills(created_at)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE bills ADD COLUMN creator TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN photo BLOB")
            db.execSQL("ALTER TABLE items ADD COLUMN photo_ver INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) replaceOldSamples(db)
        if (oldVersion < 5) renameCatalog(db)
    }

    /** Bản 5: cập nhật tên/đơn vị/giá hàng hoá theo bảng giá mới (8/11/2026). */
    private fun renameCatalog(db: SQLiteDatabase) {
        val changes = listOf(
            // tên cũ -> (tên mới, đơn vị, giá)
            "Nha đam 3Q" to Triple("Nha đam 3Q 1Kg", "Gói", 50000L),
            "Rich base" to Triple("Rick base", "Hộp", 55000L),
            "Rich lùn" to Triple("Rick lùn", "Hộp", 32000L),
            "Siro đường đen Syrup 2.5kg" to Triple("Siro đường đen Tuấn Cường Phát 2.5kg", "Chai", 130000L),
            "Trà đen phúc long" to Triple("Trà đen phúc long 500g", "Gói", 125000L),
            "Trân châu Kun han" to Triple("Trân châu Kun han 3Kg", "Gói", 90000L),
            "Vụn dừa" to Triple("Vụn dừa 500g", "Gói", 70000L),
            "Trà lài VINSAF" to Triple("Trà lài VINSAF 1Kg", "Gói", 240000L),
        )
        for ((old, new) in changes) {
            val (name, unit, price) = new
            val taken = db.rawQuery("SELECT 1 FROM items WHERE name=? COLLATE NOCASE", arrayOf(name)).use { it.moveToFirst() }
            if (taken) continue
            db.update("items", ContentValues().apply {
                put("name", name); put("unit", unit); put("price", price)
            }, "name=? COLLATE NOCASE", arrayOf(old))
        }
    }

    /**
     * Bản 4: xoá danh mục & hàng mẫu cũ (Rau củ, Thịt cá, Gia vị) rồi nạp danh mục đồ uống.
     * Chỉ xoá đúng các món mẫu; danh mục cũ còn hàng do người dùng tự thêm thì giữ lại.
     * Lịch sử bill không ảnh hưởng vì bill lưu bản chụp tên/giá.
     */
    private fun replaceOldSamples(db: SQLiteDatabase) {
        val oldItems = mapOf(
            "Rau củ" to listOf("Cà chua", "Hành lá", "Khoai tây", "Rau muống"),
            "Thịt cá" to listOf("Thịt ba chỉ", "Ức gà", "Cá basa"),
            "Gia vị" to listOf("Nước mắm", "Đường", "Muối"),
        )
        for ((cat, names) in oldItems) {
            val cid = db.rawQuery("SELECT id FROM categories WHERE name=? COLLATE NOCASE", arrayOf(cat))
                .use { if (it.moveToFirst()) it.getLong(0) else null } ?: continue
            for (n in names) db.delete("items", "category_id=? AND name=?", arrayOf(cid.toString(), n))
            db.delete("categories", "id=? AND id NOT IN (SELECT category_id FROM items)", arrayOf(cid.toString()))
        }
        seed(db)
    }

    /** Dữ liệu mẫu cho lần chạy đầu tiên. */
    private fun seed(db: SQLiteDatabase) {
        val sample = linkedMapOf(
            "Cà phê & Trà" to listOf(
                Triple("Cafe bột", "kg", 369000L), Triple("Trà đen phúc long 500g", "Gói", 125000L),
                Triple("Trà lài VINSAF 1Kg", "Gói", 240000L),
            ),
            "Siro" to listOf(
                Triple("Siro chanh xanh TE 2.5Kg", "Chai", 200000L), Triple("Siro đào berrino", "Chai", 160000L),
                Triple("Siro dâu tây berrino", "Chai", 85000L), Triple("Siro Việt quất berrino", "Chai", 115000L),
                Triple("Siro Xoài berrino", "Chai", 100000L), Triple("Siro Vải berrino", "Chai", 140000L),
                Triple("Siro đường đen Tuấn Cường Phát 2.5kg", "Chai", 130000L),
            ),
            "Sữa & Kem" to listOf(
                Triple("Bột kem trứng cô gái Xiaka", "Gói", 165000L), Triple("Rick base", "Hộp", 55000L),
                Triple("Rick lùn", "Hộp", 32000L), Triple("Sữa đặc ngôi sao xanh lá", "Hộp", 65000L),
                Triple("Sữa tươi Mlekovita", "Hộp", 35000L),
            ),
            "Topping" to listOf(
                Triple("Nha đam 3Q 1Kg", "Gói", 50000L), Triple("Trân châu Kun han 3Kg", "Gói", 90000L),
                Triple("Xí muội không hạt", "200g", 30000L), Triple("Vụn dừa 500g", "Gói", 70000L),
            ),
        )
        var sort = db.rawQuery("SELECT COALESCE(MAX(sort),0)+1 FROM categories", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        for ((cat, items) in sample) {
            val cid = db.rawQuery("SELECT id FROM categories WHERE name=? COLLATE NOCASE", arrayOf(cat))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
                ?: db.insert("categories", null, ContentValues().apply { put("name", cat); put("sort", sort++) })
            for ((n, u, p) in items) {
                val exists = db.rawQuery("SELECT 1 FROM items WHERE name=? COLLATE NOCASE", arrayOf(n))
                    .use { it.moveToFirst() }
                if (exists) continue
                db.insert("items", null, ContentValues().apply {
                    put("category_id", cid); put("name", n); put("unit", u); put("price", p)
                })
            }
        }
    }

    // ---------- Danh mục ----------
    fun categories(): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery(
            """SELECT c.id, c.name, COUNT(i.id) FROM categories c
               LEFT JOIN items i ON i.category_id = c.id
               GROUP BY c.id ORDER BY c.sort, c.name COLLATE NOCASE""", null
        ).use { c -> while (c.moveToNext()) out += Category(c.getLong(0), c.getString(1), c.getInt(2)) }
        return out
    }

    /** Trả về false nếu tên trùng. */
    fun addCategory(name: String): Boolean {
        val db = writableDatabase
        val nextSort = db.rawQuery("SELECT COALESCE(MAX(sort),0)+1 FROM categories", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return db.insert("categories", null, ContentValues().apply {
            put("name", name.trim()); put("sort", nextSort)
        }) != -1L
    }

    fun renameCategory(id: Long, name: String): Boolean = try {
        writableDatabase.updateWithOnConflict(
            "categories", ContentValues().apply { put("name", name.trim()) },
            "id=?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_ABORT
        ) > 0
    } catch (e: Exception) {
        false
    }

    /** Chỉ xoá được danh mục trống. Trả về false nếu danh mục còn nguyên liệu. */
    fun deleteCategory(id: Long): Boolean {
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM items WHERE category_id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count > 0) return false
        return db.delete("categories", "id=?", arrayOf(id.toString())) > 0
    }

    /** Xoá tất cả danh mục trống, trả về số danh mục đã xoá. */
    fun deleteEmptyCategories(): Int =
        writableDatabase.delete(
            "categories", "id NOT IN (SELECT DISTINCT category_id FROM items)", null
        )

    // ---------- Nguyên liệu ----------
    fun items(): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, category_id, name, unit, price, photo_ver FROM items ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) out += Item(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5)
            )
        }
        return out
    }

    /** Lưu nguyên liệu; nếu photoChanged thì ghi ảnh mới (null = xoá ảnh). Trả về id. */
    fun saveItem(item: Item, photo: ByteArray? = null, photoChanged: Boolean = false): Long {
        val v = ContentValues().apply {
            put("category_id", item.categoryId); put("name", item.name.trim())
            put("unit", item.unit.trim()); put("price", item.price)
            if (photoChanged) {
                if (photo == null) { putNull("photo"); put("photo_ver", 0L) }
                else { put("photo", photo); put("photo_ver", System.currentTimeMillis()) }
            }
        }
        return if (item.id == 0L) writableDatabase.insert("items", null, v)
        else { writableDatabase.update("items", v, "id=?", arrayOf(item.id.toString())); item.id }
    }

    fun photo(itemId: Long): ByteArray? =
        readableDatabase.rawQuery("SELECT photo FROM items WHERE id=?", arrayOf(itemId.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }

    fun deleteItem(id: Long) {
        writableDatabase.delete("items", "id=?", arrayOf(id.toString()))
    }

    // ---------- Bill ----------
    fun saveBill(
        note: String,
        creator: String,
        lines: List<BillLine>,
        time: Long = System.currentTimeMillis(),
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val billId = db.insert("bills", null, ContentValues().apply {
                put("created_at", time); put("total", lines.sumOf { it.amount }); put("note", note.trim())
                put("creator", creator.trim())
            })
            for (l in lines) {
                db.insert("bill_items", null, ContentValues().apply {
                    put("bill_id", billId); put("item_name", l.itemName); put("category_name", l.categoryName)
                    put("unit", l.unit); put("price", l.price); put("qty", l.qty)
                })
            }
            db.setTransactionSuccessful()
            return billId
        } finally {
            db.endTransaction()
        }
    }

    fun bills(): List<Bill> {
        val out = ArrayList<Bill>()
        readableDatabase.rawQuery(
            """SELECT b.id, b.created_at, b.total, b.note, COUNT(bi.id), b.creator FROM bills b
               LEFT JOIN bill_items bi ON bi.bill_id = b.id
               GROUP BY b.id ORDER BY b.created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) out += Bill(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4), c.getString(5))
        }
        return out
    }

    fun bill(id: Long): Bill? = bills().firstOrNull { it.id == id }

    fun billLines(billId: Long): List<BillLine> {
        val out = ArrayList<BillLine>()
        readableDatabase.rawQuery(
            "SELECT item_name, category_name, unit, price, qty FROM bill_items WHERE bill_id=? ORDER BY id",
            arrayOf(billId.toString())
        ).use { c ->
            while (c.moveToNext()) out += BillLine(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getDouble(4))
        }
        return out
    }

    fun deleteBill(id: Long) {
        writableDatabase.delete("bills", "id=?", arrayOf(id.toString()))
    }

    // ---------- Thống kê ----------
    private fun range(from: Long, to: Long) = arrayOf(from.toString(), to.toString())

    /** (tổng tiền, số bill) trong khoảng thời gian [from, to). */
    fun summary(from: Long, to: Long): Pair<Long, Int> =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(total),0), COUNT(*) FROM bills WHERE created_at>=? AND created_at<?",
            range(from, to)
        ).use { if (it.moveToFirst()) it.getLong(0) to it.getInt(1) else 0L to 0 }

    fun byCategory(from: Long, to: Long): List<StatRow> {
        val out = ArrayList<StatRow>()
        readableDatabase.rawQuery(
            """SELECT bi.category_name, SUM(ROUND(bi.price*bi.qty)) AS s FROM bill_items bi
               JOIN bills b ON b.id = bi.bill_id
               WHERE b.created_at>=? AND b.created_at<?
               GROUP BY bi.category_name ORDER BY s DESC""", range(from, to)
        ).use { c -> while (c.moveToNext()) out += StatRow(c.getString(0), c.getLong(1)) }
        return out
    }

    fun topItems(from: Long, to: Long, limit: Int = 10): List<StatRow> {
        val out = ArrayList<StatRow>()
        readableDatabase.rawQuery(
            """SELECT bi.item_name, SUM(ROUND(bi.price*bi.qty)) AS s, SUM(bi.qty), MAX(bi.unit)
               FROM bill_items bi JOIN bills b ON b.id = bi.bill_id
               WHERE b.created_at>=? AND b.created_at<?
               GROUP BY bi.item_name ORDER BY s DESC LIMIT $limit""", range(from, to)
        ).use { c -> while (c.moveToNext()) out += StatRow(c.getString(0), c.getLong(1), c.getDouble(2), c.getString(3)) }
        return out
    }

    fun byCreator(from: Long, to: Long): List<StatRow> {
        val out = ArrayList<StatRow>()
        readableDatabase.rawQuery(
            """SELECT CASE WHEN creator='' THEN '(chưa ghi tên)' ELSE creator END AS n, SUM(total) AS s, COUNT(*)
               FROM bills WHERE created_at>=? AND created_at<? GROUP BY n ORDER BY s DESC""", range(from, to)
        ).use { c -> while (c.moveToNext()) out += StatRow(c.getString(0), c.getLong(1), c.getDouble(2), "bill") }
        return out
    }

    /** Tổng chi theo ngày ("%Y-%m-%d") hoặc theo tháng ("%Y-%m"), theo giờ máy. */
    fun byPeriod(from: Long, to: Long, monthly: Boolean): List<StatRow> {
        val fmt = if (monthly) "%Y-%m" else "%Y-%m-%d"
        val out = ArrayList<StatRow>()
        readableDatabase.rawQuery(
            """SELECT strftime('$fmt', created_at/1000, 'unixepoch', 'localtime') AS d, SUM(total)
               FROM bills WHERE created_at>=? AND created_at<? GROUP BY d ORDER BY d""", range(from, to)
        ).use { c -> while (c.moveToNext()) out += StatRow(c.getString(0), c.getLong(1)) }
        return out
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import android.content.ContentValues
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ghi đè nội dung bill đã lưu (giữ nguyên số bill và thời điểm tạo). */
fun Db.updateBill(id: Long, note: String, creator: String, lines: List<BillLine>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.update("bills", ContentValues().apply {
            put("note", note.trim()); put("creator", creator.trim()); put("total", lines.sumOf { it.amount })
        }, "id=?", arrayOf(id.toString()))
        db.delete("bill_items", "bill_id=?", arrayOf(id.toString()))
        for (l in lines) {
            db.insert("bill_items", null, ContentValues().apply {
                put("bill_id", id); put("item_name", l.itemName); put("category_name", l.categoryName)
                put("unit", l.unit); put("price", l.price); put("qty", l.qty)
            })
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

/** Một dòng đang sửa: giữ chuỗi người dùng gõ để không nhảy con trỏ. */
private class EditLine(
    val name: String, val category: String, val unit: String,
    qty: String, price: String,
) {
    var qty by mutableStateOf(qty)
    var price by mutableStateOf(price)
    fun toLine(): BillLine? {
        val q = Fmt.parseQty(qty) ?: return null
        if (q <= 0) return null
        return BillLine(name, category, unit, Fmt.parseMoney(price), q)
    }
}

/** Sửa bill đã lưu. */
@Composable
fun BillEditDialog(state: AppState, bill: Bill, lines: List<BillLine>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    BillEditor(
        state, title = "Sửa bill #${bill.id}", subtitle = Fmt.dateTime(bill.createdAt),
        initialLines = lines, initialNote = bill.note, initialCreator = bill.creator,
        saveLabel = "Lưu", onCancel = onDone,
    ) { note, creator, newLines ->
        state.db.updateBill(bill.id, note, creator, newLines)
        Toast.makeText(ctx, "Đã lưu thay đổi bill #${bill.id}", Toast.LENGTH_SHORT).show()
        state.changed(); onDone()
    }
}

/** Màn sửa nội dung bill: dùng cho cả bước xác nhận trước khi xuất và sửa bill đã lưu. */
@Composable
fun BillEditor(
    state: AppState,
    title: String,
    subtitle: String?,
    initialLines: List<BillLine>,
    initialNote: String,
    initialCreator: String,
    saveLabel: String,
    onCancel: () -> Unit,
    onSave: (note: String, creator: String, lines: List<BillLine>) -> Unit,
) {
    var note by remember { mutableStateOf(initialNote) }
    var creator by remember { mutableStateOf(initialCreator) }
    val lines = initialLines
    val edit = remember {
        mutableStateListOf<EditLine>().apply {
            lines.forEach { add(EditLine(it.itemName, it.categoryName, it.unit, Fmt.qty(it.qty), Fmt.number(it.price))) }
        }
    }
    var picking by remember { mutableStateOf(false) }
    val total = edit.sumOf { it.toLine()?.amount ?: 0L }
    val invalid = edit.any { it.toLine() == null }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (subtitle != null) Text(subtitle, color = Color.Gray, fontSize = 13.sp)
                edit.forEachIndexed { i, l ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { edit.removeAt(i) }) { Icon(Icons.Filled.Delete, "Xoá dòng", tint = Color.Red) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = l.qty, onValueChange = { l.qty = it }, singleLine = true,
                            label = { Text("SL (${l.unit})") }, modifier = Modifier.weight(0.9f),
                            isError = l.toLine() == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = l.price,
                            onValueChange = { s -> val n = Fmt.parseMoney(s); l.price = if (n > 0) Fmt.number(n) else "" },
                            singleLine = true, label = { Text("Đơn giá") }, modifier = Modifier.weight(1.2f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    l.toLine()?.let { Text("= ${Fmt.money(it.amount)}", color = Green, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Thêm món") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("TỔNG CỘNG", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Fmt.money(total), fontWeight = FontWeight.Bold, color = Green)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = creator, onValueChange = { creator = it }, label = { Text("Người tạo bill") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Ghi chú") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = edit.isNotEmpty() && !invalid && creator.isNotBlank(), onClick = {
                onSave(note, creator, edit.mapNotNull { it.toLine() })
            }) { Text(saveLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Huỷ") } },
    )

    if (picking) ItemPicker(state, onDismiss = { picking = false }) { item, catName ->
        val existing = edit.firstOrNull { it.name == item.name }
        if (existing != null) {
            existing.qty = Fmt.qty((Fmt.parseQty(existing.qty) ?: 0.0) + 1)
        } else {
            edit.add(EditLine(item.name, catName, item.unit, "1", Fmt.number(item.price)))
        }
        picking = false
    }
}

/** Chọn một hàng hoá trong danh mục (có tìm kiếm). */
@Composable
private fun ItemPicker(state: AppState, onDismiss: () -> Unit, onPick: (Item, String) -> Unit) {
    val items = remember { state.db.items() }
    val cats = remember { state.db.categories().associate { it.id to it.name } }
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm món") },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("Tìm hàng hoá…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(item, cats[item.categoryId] ?: "") }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ItemThumb(state.db, item, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name)
                                Text("${Fmt.money(item.price)} / ${item.unit}", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

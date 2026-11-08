package vn.nguyenlieu.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** File nhận từ máy khác: một đơn hàng (.json) hoặc toàn bộ dữ liệu (.db). */
sealed interface Incoming {
    data class BillData(val bill: SharedBill) : Incoming
    data class DbFile(val file: File) : Incoming
}

object IncomingFile {
    /** Nhận dạng file; ném lỗi kèm thông báo nếu không dùng được. */
    fun classify(ctx: Context, uri: Uri): Incoming {
        val head = ByteArray(16)
        val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(head) }
            ?: throw IllegalArgumentException("Không đọc được file")
        if (n >= 15 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3") {
            return Incoming.DbFile(Backup.receive(ctx, uri))
        }
        val text = ctx.contentResolver.openInputStream(uri)?.use { s ->
            s.readBytes().also { require(it.size < 5_000_000) { "File quá lớn" } }.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Không đọc được file")
        val bill = BillFile.parse(text) ?: throw IllegalArgumentException("File không phải đơn hàng hay dữ liệu Mua NL")
        return Incoming.BillData(bill)
    }
}

/** Hộp thoại xử lý file nhận được (dùng chung cho Cài đặt và khi mở file từ Zalo). */
@Composable
fun IncomingDialog(state: AppState, incoming: Incoming, onDone: () -> Unit) {
    when (incoming) {
        is Incoming.BillData -> BillImportDialog(state, incoming.bill, onDone)
        is Incoming.DbFile -> DbImportDialog(state, incoming.file, onDone)
    }
}

@Composable
private fun BillImportDialog(state: AppState, bill: SharedBill, onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Thêm đơn hàng vào máy này?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Fmt.dateTime(bill.createdAt), color = Color.Gray, fontSize = 13.sp)
                if (bill.creator.isNotBlank()) Text("Người tạo: ${bill.creator}")
                if (bill.note.isNotBlank()) Text("Ghi chú: ${bill.note}")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                bill.lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${l.itemName} (${Fmt.qty(l.qty)} ${l.unit})", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Text(Fmt.money(l.amount), fontSize = 14.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("TỔNG CỘNG", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Fmt.money(bill.lines.sumOf { it.amount }), fontWeight = FontWeight.Bold, color = Green)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val id = state.db.importBill(bill)
                Toast.makeText(
                    ctx, if (id == null) "Đơn này đã có trên máy" else "Đã thêm đơn thành bill #$id",
                    Toast.LENGTH_LONG
                ).show()
                state.changed(); onDone()
            }) { Text("Thêm đơn") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Huỷ") } },
    )
}

@Composable
private fun DbImportDialog(state: AppState, file: File, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirmReplace by remember { mutableStateOf(false) }
    fun close() { file.delete(); onDone() }

    if (!confirmReplace) AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Nhập dữ liệu") },
        text = {
            Text(
                "• Gộp: thêm danh mục, hàng hoá và bill chưa có; giữ nguyên dữ liệu trên máy này.\n\n" +
                    "• Thay thế: xoá toàn bộ dữ liệu trên máy này và dùng dữ liệu trong file."
            )
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val r = Backup.merge(ctx, file)
                    Toast.makeText(
                        ctx, "Đã thêm ${r.categories} danh mục, ${r.items} hàng hoá, ${r.bills} bill",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Lỗi gộp dữ liệu: ${e.message}", Toast.LENGTH_LONG).show()
                }
                state.changed(); close()
            }) { Text("Gộp") }
        },
        dismissButton = { TextButton(onClick = { confirmReplace = true }) { Text("Thay thế", color = Color.Red) } },
    ) else AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text("Thay thế toàn bộ?") },
        text = { Text("Mọi danh mục, hàng hoá và lịch sử bill hiện có trên máy này sẽ bị xoá. Không thể hoàn tác.") },
        confirmButton = {
            TextButton(onClick = {
                try {
                    Backup.replace(ctx, file)
                    state.cart.clear()
                    Toast.makeText(ctx, "Đã thay thế dữ liệu", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                }
                state.changed(); close()
            }) { Text("Thay thế", color = Color.Red) }
        },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Huỷ") } },
    )
}

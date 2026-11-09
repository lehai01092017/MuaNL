@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HistoryScreen(state: AppState) {
    val bills = remember(state.version) { state.db.bills() }
    var openId by remember { mutableStateOf<Long?>(null) }

    if (bills.isEmpty()) {
        EmptyHint("Chưa có bill nào.\nChọn nguyên liệu ở tab \"Mua hàng\" rồi bấm \"Xuất bill\".")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        items(bills, key = { it.id }) { b ->
            Card(Modifier.fillMaxWidth().padding(4.dp).clickable { openId = b.id }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bill #${b.id}", fontWeight = FontWeight.Bold)
                        Text(Fmt.dateTime(b.createdAt), fontSize = 13.sp, color = Color.Gray)
                        if (b.creator.isNotBlank()) Text("👤 ${b.creator}", fontSize = 13.sp, maxLines = 1)
                        if (b.note.isNotBlank()) Text(b.note, fontSize = 13.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.money(b.total), fontWeight = FontWeight.Bold, color = Green)
                        Text("${b.lineCount} món", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
    openId?.let { BillDialog(state, it, onDismiss = { openId = null }) }
}

/** Xem chi tiết một bill, chia sẻ (ảnh / văn bản / Zalo) và xoá. */
@Composable
fun BillDialog(state: AppState, billId: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val bill = remember(billId, state.version) { state.db.bill(billId) } ?: return
    val lines = remember(billId, state.version) { state.db.billLines(billId) }
    var askDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Bill #${bill.id}")
                Text(Fmt.dateTime(bill.createdAt), fontSize = 13.sp, color = Color.Gray)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bill.creator.isNotBlank()) Text("Người tạo: ${bill.creator}", fontSize = 14.sp)
                if (bill.note.isNotBlank()) Text("Ghi chú: ${bill.note}", fontSize = 14.sp)
                lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(l.itemName, fontWeight = FontWeight.SemiBold)
                            Text("${Fmt.qty(l.qty)} ${l.unit} × ${Fmt.number(l.price)}", fontSize = 13.sp, color = Color.Gray)
                        }
                        Text(Fmt.money(l.amount))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("TỔNG CỘNG", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Fmt.money(bill.total), fontWeight = FontWeight.Bold, color = Green)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text("Sửa bill")
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { BillShare.shareImageAndFile(ctx, bill, lines, BillShare.ZALO) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0068FF)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Gửi Zalo: ảnh bill + file đơn") }
                Text(
                    "File đơn (.json) giúp máy nhận bấm mở bằng Mua NL để thêm đơn này vào lịch sử.",
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { BillShare.shareImage(ctx, bill, lines) }, modifier = Modifier.weight(1f)) {
                        Text("Ảnh")
                    }
                    OutlinedButton(onClick = { BillShare.shareText(ctx, bill, lines) }, modifier = Modifier.weight(1f)) {
                        Text("Chữ")
                    }
                    OutlinedButton(onClick = { BillShare.shareBillFile(ctx, bill, lines) }, modifier = Modifier.weight(1f)) {
                        Text("File đơn")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        dismissButton = {
            TextButton(onClick = { askDelete = true }) {
                Icon(Icons.Filled.Delete, null, tint = Color.Red); Text("Xoá bill", color = Color.Red)
            }
        },
    )

    if (editing) BillEditDialog(state, bill, lines) { editing = false }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Xoá bill #${bill.id}?") },
            text = { Text("Bill sẽ bị xoá khỏi lịch sử và thống kê.") },
            confirmButton = {
                TextButton(onClick = {
                    state.db.deleteBill(bill.id); askDelete = false; onDismiss(); state.changed()
                }) { Text("Xoá", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Huỷ") } },
        )
    }
}

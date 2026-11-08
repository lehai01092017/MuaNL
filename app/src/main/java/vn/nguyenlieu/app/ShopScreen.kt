@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ShopScreen(state: AppState) {
    val ctx = LocalContext.current
    val v = state.version
    val categories = remember(v) { state.db.categories() }
    val items = remember(v) { state.db.items() }
    val itemById = remember(items) { items.associateBy { it.id } }
    val collapsed = remember { mutableStateListOf<Long>() }
    var query by remember { mutableStateOf("") }
    var editQty by remember { mutableStateOf<Item?>(null) }
    var showCheckout by remember { mutableStateOf(false) }
    var savedBillId by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // Bỏ khỏi giỏ những nguyên liệu đã bị xoá
    LaunchedEffect(itemById) {
        state.cart.keys.filter { it !in itemById }.forEach { state.cart.remove(it) }
    }

    val cartLines = state.cart.mapNotNull { (id, q) ->
        val it = itemById[id] ?: return@mapNotNull null
        val cat = categories.firstOrNull { c -> c.id == it.categoryId }?.name ?: ""
        BillLine(it.name, cat, it.unit, it.price, q)
    }.sortedBy { it.categoryName + it.itemName }
    val total = cartLines.sumOf { it.amount }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Tìm nguyên liệu…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Xoá") }
            },
            singleLine = true,
        )

        if (items.isEmpty()) {
            EmptyHint("Chưa có nguyên liệu nào.\nVào tab \"Hàng hoá\" để thêm.")
        }

        LazyColumn(Modifier.weight(1f)) {
            for (cat in categories) {
                val catItems = items.filter {
                    it.categoryId == cat.id && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
                }
                if (catItems.isEmpty()) continue
                val isCollapsed = query.isBlank() && cat.id in collapsed
                val picked = catItems.count { it.id in state.cart }
                item(key = "c${cat.id}") {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { if (isCollapsed) collapsed.remove(cat.id) else collapsed.add(cat.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (isCollapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null)
                        Spacer(Modifier.width(6.dp))
                        Text(cat.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (picked > 0) "đã chọn $picked/${catItems.size}" else "${catItems.size} món",
                            fontSize = 13.sp, color = Color.DarkGray)
                    }
                }
                if (!isCollapsed) {
                    items(catItems, key = { "i${it.id}" }) { item ->
                        ShopItemRow(
                            db = state.db,
                            onPhoto = { viewing = Photos.cached(state.db, item.id, item.photoVer) },
                            item = item,
                            qty = state.cart[item.id] ?: 0.0,
                            onChange = { q -> if (q <= 0.0) state.cart.remove(item.id) else state.cart[item.id] = q },
                            onEditQty = { editQty = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        if (cartLines.isNotEmpty()) {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${cartLines.size} món", fontSize = 13.sp)
                        Text(Fmt.money(total), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Green)
                    }
                    TextButton(onClick = { state.cart.clear() }) { Text("Bỏ chọn") }
                    Button(onClick = { showCheckout = true }) { Text("Xuất bill") }
                }
            }
        }
    }

    editQty?.let { item ->
        QtyDialog(item, state.cart[item.id] ?: 0.0, onDismiss = { editQty = null }) { q ->
            if (q <= 0.0) state.cart.remove(item.id) else state.cart[item.id] = q
            editQty = null
        }
    }

    if (showCheckout) {
        CheckoutDialog(cartLines, total, Prefs.creator(ctx), onDismiss = { showCheckout = false }) { note, creator ->
            Prefs.setCreator(ctx, creator)
            val id = state.db.saveBill(note, creator, cartLines)
            state.cart.clear()
            state.changed()
            showCheckout = false
            savedBillId = id
            Toast.makeText(ctx, "Đã lưu bill #$id", Toast.LENGTH_SHORT).show()
        }
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }

    savedBillId?.let { id ->
        BillDialog(state, id, onDismiss = { savedBillId = null })
    }
}

@Composable
private fun ShopItemRow(
    db: Db,
    onPhoto: () -> Unit,
    item: Item,
    qty: Double,
    onChange: (Double) -> Unit,
    onEditQty: () -> Unit,
) {
    val selected = qty > 0
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFFF1F8E9) else Color.Transparent)
            .clickable { if (!selected) onChange(1.0) else onEditQty() }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemThumb(db, item, 48.dp, onClick = if (item.photoVer != 0L) onPhoto else null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.money(item.price)} / ${item.unit.ifBlank { "đv" }}", fontSize = 13.sp, color = Color.Gray)
            if (selected) Text("= ${Fmt.money(BillLine(item.name, "", item.unit, item.price, qty).amount)}",
                fontSize = 13.sp, color = Green)
        }
        if (selected) {
            FilledTonalIconButton(onClick = { onChange((qty - 1).coerceAtLeast(0.0)) }, modifier = Modifier.size(36.dp)) {
                Text("−", fontSize = 20.sp)
            }
            Text(
                Fmt.qty(qty),
                modifier = Modifier.width(56.dp).clickable { onEditQty() },
                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        FilledTonalIconButton(onClick = { onChange(qty + 1) }, modifier = Modifier.size(36.dp)) {
            Text("+", fontSize = 20.sp)
        }
    }
}

@Composable
private fun QtyDialog(item: Item, current: Double, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (current > 0) Fmt.qty(current) else "") }
    val q = Fmt.parseQty(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Số lượng (${item.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("0,5", "1", "2", "5", "10").forEach { s ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable { text = s }
                        ) { Text(s, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                    }
                }
                if (q != null && q > 0) {
                    Spacer(Modifier.size(8.dp))
                    Text("Thành tiền: " + Fmt.money(BillLine(item.name, "", item.unit, item.price, q).amount), color = Green)
                }
            }
        },
        confirmButton = { TextButton(enabled = q != null && q >= 0, onClick = { onOk(q ?: 0.0) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (current > 0) TextButton(onClick = { onOk(0.0) }) { Text("Bỏ chọn", color = Color.Red) }
                TextButton(onClick = onDismiss) { Text("Huỷ") }
            }
        },
    )
}

@Composable
private fun CheckoutDialog(
    lines: List<BillLine>,
    total: Long,
    initialCreator: String,
    onDismiss: () -> Unit,
    onSave: (note: String, creator: String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf(initialCreator) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xác nhận bill") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    Text(Fmt.money(total), fontWeight = FontWeight.Bold, color = Green)
                }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = creator, onValueChange = { creator = it },
                    label = { Text("Người tạo bill") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Ghi chú (người mua, nơi mua…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(note, creator) }) { Text("Lưu & chia sẻ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text, color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(32.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

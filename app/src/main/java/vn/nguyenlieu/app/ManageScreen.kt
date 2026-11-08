@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

private sealed interface Confirm {
    data class DelCategory(val c: Category) : Confirm
    data class DelItem(val i: Item) : Confirm
    data class CleanEmpty(val count: Int) : Confirm
}

@Composable
fun ManageScreen(state: AppState) {
    val ctx = LocalContext.current
    val v = state.version
    val categories = remember(v) { state.db.categories() }
    val items = remember(v) { state.db.items() }
    val expanded = remember { mutableStateListOf<Long>() }

    var catDialog by remember { mutableStateOf<Category?>(null) }   // id=0 => thêm mới
    var itemDialog by remember { mutableStateOf<Item?>(null) }       // id=0 => thêm mới
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }    // ảnh chụp nhanh cho hàng mới
    var viewing by remember { mutableStateOf<ImageBitmap?>(null) }
    val camUri = remember { Photos.newCameraUri(ctx) }
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val rows = ExcelImport.read(ctx, uri)
                val r = state.db.importCatalog(rows)
                Toast.makeText(
                    ctx,
                    "Đã nhập ${rows.size} dòng: thêm ${r.added}, cập nhật giá ${r.updated}" +
                        (if (r.newCategories > 0) ", tạo ${r.newCategories} danh mục" else ""),
                    Toast.LENGTH_LONG
                ).show()
                state.changed()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Không đọc được file Excel", Toast.LENGTH_LONG).show()
            }
        }
    }
    val quickCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val bytes = Photos.load(ctx, camUri)
            if (bytes == null) Toast.makeText(ctx, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
            else {
                newPhoto = bytes
                val cat = expanded.lastOrNull() ?: categories.first().id
                itemDialog = Item(0, cat, "", "", 0)
            }
        }
    }

    val emptyCount = categories.count { it.itemCount == 0 }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("${categories.size} danh mục · ${items.size} mặt hàng", color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { excelPicker.launch(arrayOf("*/*")) }) { Text("📄 Nhập từ Excel") }
                        OutlinedButton(
                            enabled = emptyCount > 0,
                            onClick = { confirm = Confirm.CleanEmpty(emptyCount) }
                        ) { Text(if (emptyCount > 0) "Xoá $emptyCount DM trống" else "Không có DM trống") }
                    }
                }
            }
            if (categories.isEmpty()) item { EmptyHint("Chưa có danh mục. Bấm \"Thêm danh mục\" để bắt đầu.") }

            for (cat in categories) {
                val open = cat.id in expanded
                item(key = "c${cat.id}") {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { if (open) expanded.remove(cat.id) else expanded.add(cat.id) }
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (cat.itemCount == 0) "Trống" else "${cat.itemCount} nguyên liệu",
                                fontSize = 12.sp, color = if (cat.itemCount == 0) Color(0xFFE65100) else Color.DarkGray
                            )
                        }
                        IconButton(onClick = { catDialog = cat }) { Icon(Icons.Filled.Edit, "Sửa") }
                        IconButton(onClick = {
                            if (cat.itemCount > 0) Toast.makeText(
                                ctx, "Danh mục còn ${cat.itemCount} nguyên liệu — chỉ xoá được danh mục trống",
                                Toast.LENGTH_LONG
                            ).show()
                            else confirm = Confirm.DelCategory(cat)
                        }) {
                            Icon(Icons.Filled.Delete, "Xoá",
                                tint = if (cat.itemCount == 0) Color.Red else Color.LightGray)
                        }
                    }
                }
                if (open) {
                    items(items.filter { it.categoryId == cat.id }, key = { "i${it.id}" }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { itemDialog = item }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ItemThumb(state.db, item, 52.dp, onClick = if (item.photoVer != 0L) ({
                                viewing = Photos.cached(state.db, item.id, item.photoVer)
                            }) else ({ itemDialog = item }))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name)
                                Text("${Fmt.money(item.price)} / ${item.unit.ifBlank { "đv" }}",
                                    fontSize = 13.sp, color = Color.Gray)
                            }
                            IconButton(onClick = { itemDialog = item }) { Icon(Icons.Filled.Edit, "Sửa") }
                            IconButton(onClick = { confirm = Confirm.DelItem(item) }) {
                                Icon(Icons.Filled.Delete, "Xoá", tint = Color.Red)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 80.dp))
                    }
                    item(key = "add${cat.id}") {
                        TextButton(
                            onClick = { itemDialog = Item(0, cat.id, "", "", 0) },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Thêm hàng hoá")
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(80.dp)) }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (categories.isEmpty()) Toast.makeText(ctx, "Tạo danh mục trước đã", Toast.LENGTH_SHORT).show()
                    else quickCamera.launch(camUri)
                },
                icon = { Icon(Icons.Filled.Face, null) },
                text = { Text("Chụp ảnh thêm hàng") },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            ExtendedFloatingActionButton(
                onClick = { catDialog = Category(0, "", 0) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Thêm danh mục") },
            )
        }
    }

    catDialog?.let { cat ->
        NameDialog(
            title = if (cat.id == 0L) "Thêm danh mục" else "Sửa danh mục",
            initial = cat.name,
            onDismiss = { catDialog = null },
        ) { name ->
            val ok = if (cat.id == 0L) state.db.addCategory(name) else state.db.renameCategory(cat.id, name)
            if (ok) {
                catDialog = null; state.changed()
            } else Toast.makeText(ctx, "Tên danh mục \"$name\" đã tồn tại", Toast.LENGTH_SHORT).show()
        }
    }

    itemDialog?.let { item ->
        ItemDialog(state.db, item, categories, newPhoto,
            onDismiss = { itemDialog = null; newPhoto = null }) { saved, photo, photoChanged ->
            state.db.saveItem(saved, photo, photoChanged)
            newPhoto = null
            if (saved.categoryId !in expanded) expanded.add(saved.categoryId)
            itemDialog = null; state.changed()
        }
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }

    confirm?.let { c ->
        val (title, msg) = when (c) {
            is Confirm.DelCategory -> "Xoá danh mục?" to "Xoá danh mục trống \"${c.c.name}\"."
            is Confirm.DelItem -> "Xoá nguyên liệu?" to
                "Xoá \"${c.i.name}\". Các bill cũ vẫn giữ nguyên thông tin."
            is Confirm.CleanEmpty -> "Dọn danh mục trống?" to
                "Xoá ${c.count} danh mục không có nguyên liệu nào."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    when (c) {
                        is Confirm.DelCategory -> if (!state.db.deleteCategory(c.c.id))
                            Toast.makeText(ctx, "Danh mục không trống", Toast.LENGTH_SHORT).show()
                        is Confirm.DelItem -> {
                            state.db.deleteItem(c.i.id); state.cart.remove(c.i.id)
                        }
                        is Confirm.CleanEmpty -> {
                            val n = state.db.deleteEmptyCategories()
                            Toast.makeText(ctx, "Đã xoá $n danh mục trống", Toast.LENGTH_SHORT).show()
                        }
                    }
                    confirm = null; state.changed()
                }) { Text("Xoá", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên danh mục") }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim()) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
private fun ItemDialog(
    db: Db,
    item: Item,
    categories: List<Category>,
    initialPhoto: ByteArray?,
    onDismiss: () -> Unit,
    onSave: (item: Item, photo: ByteArray?, photoChanged: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var photo by remember { mutableStateOf(initialPhoto) }
    var photoChanged by remember { mutableStateOf(initialPhoto != null) }
    val shown: ImageBitmap? = remember(photo, photoChanged) {
        when {
            photo != null -> Photos.decode(photo!!)
            !photoChanged && item.photoVer != 0L -> Photos.cached(db, item.id, item.photoVer)
            else -> null
        }
    }
    val camUri = remember { Photos.newCameraUri(ctx) }
    fun accept(bytes: ByteArray?) {
        if (bytes == null) Toast.makeText(ctx, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
        else { photo = bytes; photoChanged = true }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) accept(Photos.load(ctx, camUri))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) accept(Photos.load(ctx, uri))
    }

    var name by remember { mutableStateOf(item.name) }
    var unit by remember { mutableStateOf(item.unit) }
    var price by remember { mutableStateOf(if (item.price > 0) Fmt.number(item.price) else "") }
    var catId by remember { mutableStateOf(item.categoryId) }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.id == 0L) "Thêm hàng hoá" else "Sửa hàng hoá") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (shown != null) Image(shown, null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
                        else Text("Chưa có\nảnh", color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedButton(onClick = { camera.launch(camUri) }) { Text("📷 Chụp ảnh") }
                        OutlinedButton(onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("🖼 Chọn ảnh") }
                        if (shown != null) TextButton(onClick = { photo = null; photoChanged = true }) {
                            Text("Xoá ảnh", color = Color.Red)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên hàng hoá") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = unit, onValueChange = { unit = it },
                    label = { Text("Đơn vị (kg, bó, chai…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = price,
                    onValueChange = { s -> val n = Fmt.parseMoney(s); price = if (n > 0) Fmt.number(n) else "" },
                    label = { Text("Đơn giá (đ)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.padding(4.dp))
                Text("Danh mục", fontSize = 12.sp, color = Color.Gray)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(categories.firstOrNull { it.id == catId }?.name ?: "Chọn danh mục", modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { catId = c.id; menuOpen = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && categories.any { it.id == catId },
                onClick = {
                    onSave(item.copy(name = name, unit = unit, price = Fmt.parseMoney(price), categoryId = catId), photo, photoChanged)
                }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

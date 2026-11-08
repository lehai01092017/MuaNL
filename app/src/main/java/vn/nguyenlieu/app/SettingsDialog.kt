@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.creator(ctx)) }
    var incoming by remember { mutableStateOf<Incoming?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                incoming = IncomingFile.classify(ctx, uri)
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "File không hợp lệ", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { Prefs.setCreator(ctx, name); onDismiss() },
        title = { Text("Cài đặt") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Tên người tạo bill") },
                    supportingText = { Text("In trên mọi bill tạo từ máy này") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.height(1.dp))
                Spacer(Modifier.height(12.dp))
                Text("Chia sẻ dữ liệu sang máy khác", fontWeight = FontWeight.Bold)
                Text(
                    "Gửi file dữ liệu (danh mục, nguyên liệu, lịch sử bill) qua Zalo, Drive, Bluetooth… " +
                        "Máy nhận mở app > Cài đặt > Nhập dữ liệu. File đơn hàng (.json) gửi kèm bill cũng nhập ở đây.",
                    fontSize = 13.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    try { Backup.export(ctx) } catch (e: Exception) {
                        Toast.makeText(ctx, "Lỗi xuất dữ liệu: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Xuất & gửi dữ liệu")
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Nhập dữ liệu / đơn hàng từ file")
                }
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.setCreator(ctx, name); onDismiss() }) { Text("Xong") } },
    )

    incoming?.let { IncomingDialog(state, it) { incoming = null } }
}

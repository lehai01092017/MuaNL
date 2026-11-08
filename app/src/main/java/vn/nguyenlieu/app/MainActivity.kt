@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

val Green = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    secondary = Color(0xFF558B2F),
                    primaryContainer = Color(0xFFC8E6C9),
                    secondaryContainer = Color(0xFFDCEDC8),
                )
            ) { App() }
        }
    }
}

/** Trạng thái dùng chung giữa các tab. */
class AppState(val db: Db) {
    /** Tăng lên mỗi khi dữ liệu thay đổi để các màn hình tải lại. */
    var version by mutableIntStateOf(0)
    fun changed() { version++ }

    /** Giỏ hàng: itemId -> số lượng. */
    val cart: SnapshotStateMap<Long, Double> = mutableStateMapOf()
}

private enum class Tab(val title: String, val icon: ImageVector) {
    Shop("Mua hàng", Icons.Filled.ShoppingCart),
    Manage("Hàng hoá", Icons.Filled.Menu),
    History("Lịch sử", Icons.Filled.List),
    Stats("Thống kê", Icons.Filled.DateRange),
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val state = remember { AppState(Db.get(ctx)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val current = Tab.entries[tab]

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (current) {
                            Tab.Shop -> "Mua NL"
                            Tab.Manage -> "Danh mục hàng hoá"
                            else -> current.title
                        }
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Cài đặt", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green, titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (t == Tab.Shop && state.cart.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(state.cart.size.toString()) } }) {
                                    Icon(t.icon, contentDescription = t.title)
                                }
                            } else Icon(t.icon, contentDescription = t.title)
                        },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (current) {
                Tab.Shop -> ShopScreen(state)
                Tab.Manage -> ManageScreen(state)
                Tab.History -> HistoryScreen(state)
                Tab.Stats -> StatsScreen(state)
            }
        }
    }
    if (showSettings) SettingsDialog(state, onDismiss = { showSettings = false })
}

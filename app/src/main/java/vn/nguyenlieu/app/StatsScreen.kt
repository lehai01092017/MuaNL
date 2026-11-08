@file:OptIn(ExperimentalMaterial3Api::class)

package vn.nguyenlieu.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

private enum class Period(val label: String) { Today("Hôm nay"), Week("7 ngày"), Month("Tháng này"), Year("Năm nay"), All("Tất cả") }

private fun startOfDay(c: Calendar) = c.apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** Khoảng thời gian [from, to) tính theo giờ máy. */
private fun rangeOf(p: Period): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val c = startOfDay(Calendar.getInstance())
    val from = when (p) {
        Period.Today -> c.timeInMillis
        Period.Week -> c.apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
        Period.Month -> c.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        Period.Year -> c.apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        Period.All -> 0L
    }
    return from to now + 1
}

private val Palette = listOf(
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFFC62828), Color(0xFF00838F), Color(0xFF9E9D24), Color(0xFF4E342E),
)

@Composable
fun StatsScreen(state: AppState) {
    var pIndex by rememberSaveable { mutableIntStateOf(1) }
    val period = Period.entries[pIndex]
    val v = state.version
    val (from, to) = remember(pIndex, v) { rangeOf(period) }
    val monthly = period == Period.Year || period == Period.All
    val summary = remember(from, v) { state.db.summary(from, to) }
    val byCat = remember(from, v) { state.db.byCategory(from, to) }
    val top = remember(from, v) { state.db.topItems(from, to) }
    val series = remember(from, v) { state.db.byPeriod(from, to, monthly) }
    val byCreator = remember(from, v) { state.db.byCreator(from, to) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEachIndexed { i, p ->
                FilterChip(selected = i == pIndex, onClick = { pIndex = i }, label = { Text(p.label) })
            }
        }
        Spacer(Modifier.height(8.dp))

        val (total, count) = summary
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Tổng chi", Fmt.money(total), Modifier.weight(1.4f))
            StatCard("Số bill", count.toString(), Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(8.dp))
        StatCard("Trung bình / bill", if (count > 0) Fmt.money(total / count) else "—", Modifier.fillMaxWidth())

        if (count == 0) {
            EmptyHint("Chưa có dữ liệu trong khoảng thời gian này.")
            return@Column
        }

        if (period != Period.Today) {
            Section(if (monthly) "Chi theo tháng" else "Chi theo ngày")
            BarChart(series.map { (if (monthly) it.label.substring(5) + "/" + it.label.substring(2, 4)
                                   else it.label.substring(8) + "/" + it.label.substring(5, 7)) to it.amount })
        }

        Section("Theo danh mục")
        val catMax = byCat.maxOfOrNull { it.amount } ?: 1L
        byCat.forEachIndexed { i, r ->
            HBar(r.label, Fmt.money(r.amount) + "  (${pct(r.amount, total)})", r.amount, catMax, Palette[i % Palette.size])
        }

        if (byCreator.size > 1 || byCreator.any { it.label != "(chưa ghi tên)" }) {
            Section("Theo người tạo")
            val crMax = byCreator.maxOfOrNull { it.amount } ?: 1L
            byCreator.forEachIndexed { i, r ->
                HBar(r.label, "${Fmt.qty(r.qty)} bill · ${Fmt.money(r.amount)}", r.amount, crMax,
                    Palette[(i + 1) % Palette.size])
            }
        }

        Section("Top nguyên liệu (theo tiền)")
        val topMax = top.maxOfOrNull { it.amount } ?: 1L
        top.forEachIndexed { i, r ->
            HBar("${i + 1}. ${r.label}", "${Fmt.qty(r.qty)} ${r.unit} · ${Fmt.money(r.amount)}", r.amount, topMax, Green)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun pct(part: Long, total: Long): String =
    if (total <= 0) "0%" else String.format(java.util.Locale.US, "%.0f%%", part * 100.0 / total)

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 13.sp, color = Color.DarkGray)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green, maxLines = 1)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun HBar(label: String, value: String, amount: Long, max: Long, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
            Text(value, fontSize = 13.sp, color = Color.DarkGray)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE8F5E9))) {
            Box(
                Modifier.fillMaxWidth((amount.toFloat() / max.coerceAtLeast(1)).coerceIn(0.02f, 1f))
                    .fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(color)
            )
        }
    }
}

@Composable
private fun BarChart(data: List<Pair<String, Long>>) {
    val max = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1L
    Row(
        Modifier.horizontalScroll(rememberScrollState()).height(170.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                Text(short(value), fontSize = 10.sp, textAlign = TextAlign.Center)
                Box(
                    Modifier.width(26.dp).height((120f * value / max).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Green)
                )
                Text(label, fontSize = 10.sp, color = Color.DarkGray)
            }
        }
    }
}

/** 1250000 -> "1,3tr", 85000 -> "85k" */
private fun short(v: Long): String = when {
    v >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f", v / 1_000_000.0).replace('.', ',') + "tr"
    v >= 1_000 -> "${v / 1000}k"
    else -> v.toString()
}

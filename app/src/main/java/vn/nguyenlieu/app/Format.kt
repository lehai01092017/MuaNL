package vn.nguyenlieu.app

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val symbols = DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }
    private val moneyFmt = DecimalFormat("#,##0", symbols)
    private val qtyFmt = DecimalFormat("#,##0.##", symbols)

    fun money(v: Long): String = moneyFmt.format(v) + "đ"
    fun number(v: Long): String = moneyFmt.format(v)
    fun qty(v: Double): String = qtyFmt.format(v)

    fun dateTime(ms: Long): String = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(ms))
    fun date(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(ms))

    /** "120.000" -> 120000; bỏ mọi ký tự không phải số. */
    fun parseMoney(s: String): Long = s.filter { it.isDigit() }.take(15).toLongOrNull() ?: 0L

    /** Nhận cả "1,5" và "1.5". */
    fun parseQty(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
}

package app.listentogether.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

actual fun wallClockText(epochMs: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "M月d日 HH:mm"
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(epochMs))
}

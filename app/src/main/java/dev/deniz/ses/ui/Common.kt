package dev.deniz.ses.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun Theme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val tr = Locale.forLanguageTag("tr")

/** "14:05", "Dün 09:12", "3 Kas 18:40", "3 Kas 2025" — the way a chat list reads. */
fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
    if (t <= 0) return ""
    val a = Calendar.getInstance().apply { timeInMillis = t }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
    val dayDiff = if (sameYear) b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR) else 999
    val hm = SimpleDateFormat("HH:mm", tr).format(t)
    return when {
        dayDiff == 0 -> hm
        dayDiff == 1 -> "Dün $hm"
        sameYear -> SimpleDateFormat("d MMM", tr).format(t) + " $hm"
        else -> SimpleDateFormat("d MMM yyyy", tr).format(t)
    }
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

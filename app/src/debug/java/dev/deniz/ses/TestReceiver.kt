package dev.deniz.ses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.deniz.ses.wa.VoiceHint

/**
 * Debug only. Fakes what WaListener would hand over for "🎤 Sesli mesaj (m:ss)" from [sender], with
 * post time = now: exercises file matching → two passes → result notification without a real message.
 * adb shell am broadcast -n <pkg>/dev.deniz.ses.TestReceiver --es sender Ayşe --ei dur 5 --el back 0
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val now = System.currentTimeMillis() - i.getLongExtra("back", 0)
        val hint = VoiceHint(
            "com.whatsapp", i.getStringExtra("sender") ?: "Test", i.getStringExtra("chat"), now, now,
            i.getIntExtra("dur", -1).takeIf { it >= 0 },
        )
        App.jobs.onHints(listOf(hint), null)
    }
}

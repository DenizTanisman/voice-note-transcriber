package dev.deniz.ses.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.deniz.ses.App

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_COPY) return
        val v = App.db.get(intent.getStringExtra(Notifier.EXTRA_ID) ?: return) ?: return
        copy(ctx, v.text)
    }

    companion object {
        fun copy(ctx: Context, text: String) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sesli mesaj", text))
            Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
        }
    }
}

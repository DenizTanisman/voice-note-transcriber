package dev.deniz.ses.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.deniz.ses.R
import dev.deniz.ses.data.State
import dev.deniz.ses.data.Voice
import dev.deniz.ses.ui.MainActivity

object Notifier {
    private const val CH_TEXT = "metin"
    const val CH_WORK = "calisma"
    const val ACTION_COPY = "dev.deniz.ses.COPY"
    const val EXTRA_ID = "id"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // High importance → it peeks over the screen, but silent: WhatsApp already rang for this message.
        nm.createNotificationChannel(
            NotificationChannel(CH_TEXT, "Yazıya dökülen sesli mesajlar", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Gelen WhatsApp sesli mesajlarının yazısı"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WORK, "Yazıya dökme sürüyor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Whisper çalışırken görünen sessiz bildirim"
            },
        )
    }

    fun result(ctx: Context, v: Voice, openChat: PendingIntent?, refining: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) { android.util.Log.w("SesNotify", "notifications disabled"); return }
        android.util.Log.i("SesNotify", "post ${v.id.substringAfterLast('/')} pass=${v.pass} refining=$refining")
        val nid = v.id.hashCode()
        val title = "🎧 ${v.label ?: "Sesli mesaj"}"
        val body = when (v.state) {
            State.EMPTY -> "(konuşma duyulmadı)"
            else -> v.text
        }
        val open = openChat ?: PendingIntent.getActivity(
            ctx, nid, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ID, v.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val copy = PendingIntent.getBroadcast(
            ctx, nid, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_COPY).putExtra(EXTRA_ID, v.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH_TEXT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSubText(listOfNotNull(durationText(v.durationMs), if (refining) "iyileştiriliyor…" else null).joinToString(" · ").ifEmpty { null })
            .setOnlyAlertOnce(true)
            // Android drops notifications whose time is weeks old (a phone that was offline): cap at one day back.
            .setWhen(maxOf(if (v.msgTime > 0) v.msgTime else v.fileTime, System.currentTimeMillis() - 86_400_000L))
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(Notification.Action.Builder(null, "Kopyala", copy).build())
            .build()
        nm.notify(nid, n)
    }

    fun durationText(ms: Long): String? {
        if (ms <= 0) return null
        val s = ((ms + 500) / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}

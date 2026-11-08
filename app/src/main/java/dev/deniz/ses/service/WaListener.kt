package dev.deniz.ses.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.deniz.ses.App
import dev.deniz.ses.BuildConfig
import dev.deniz.ses.wa.Msg
import dev.deniz.ses.wa.NotifData
import dev.deniz.ses.wa.VoiceNotif

/** Watches WhatsApp notifications only, and only for voice-message lines. Nothing is cancelled or changed. */
class WaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Voice notes already waiting in the shade when the listener (re)starts.
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in VoiceNotif.PACKAGES) return
        val data = extract(sbn)
        val hints = VoiceNotif.hints(data)
        // Counts only: message text never goes to the log.
        if (BuildConfig.DEBUG) Log.d(TAG, "wa: group=${data.isGroup} summary=${data.isSummary} msgs=${data.messages.size} voice=${hints.size}")
        if (hints.isNotEmpty()) App.jobs.onHints(hints, sbn.notification.contentIntent)
    }

    private fun extract(sbn: StatusBarNotification): NotifData {
        val n = sbn.notification
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val conv = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val msgs = runCatching {
            @Suppress("DEPRECATION")
            val arr = x.getParcelableArray(Notification.EXTRA_MESSAGES)
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr).map {
                val who = it.senderPerson?.name?.toString() ?: it.sender?.toString()
                Msg(it.text?.toString().orEmpty(), it.timestamp, who, self = who == null)
            }
        }.getOrDefault(emptyList()).ifEmpty {
            // Older/plain style: a single line in EXTRA_TEXT.
            listOfNotNull(x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { Msg(it, sbn.postTime, null) })
        }
        return NotifData(
            pkg = sbn.packageName, title = title, conversation = conv, isGroup = isGroup, messages = msgs,
            postTime = sbn.postTime, isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    companion object { const val TAG = "SesListener" }
}

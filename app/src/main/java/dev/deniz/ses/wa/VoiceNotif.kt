package dev.deniz.ses.wa

/** One message line of a WhatsApp MessagingStyle notification. [self]: lines without a sender are the owner's own. */
data class Msg(val text: String, val timeMs: Long, val sender: String?, val self: Boolean = false)

/** What a listener sees of one WhatsApp notification, already pulled out of the extras. */
data class NotifData(
    val pkg: String,
    val title: String?,
    val conversation: String?,
    val isGroup: Boolean,
    val messages: List<Msg>,
    val postTime: Long,
    val isSummary: Boolean,
)

/** "Somebody sent a voice message": the file to look for and who to show it as. */
data class VoiceHint(
    val pkg: String,
    val sender: String,
    val chat: String?,
    val msgTime: Long,
    val postTime: Long,
    val durationSec: Int?,
) {
    /** Stable across re-posts: WhatsApp re-sends the whole conversation history with every new message. */
    val key: String get() = "$pkg|${chat.orEmpty()}|$sender|$msgTime"
    val label: String get() = if (chat != null && chat != sender) "$sender · $chat" else sender
}

object VoiceNotif {
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    // "🎤 Sesli mesaj (0:12)", "Ses kaydı (1:05)", "🎤 Voice message (0:07)"; a bare 🎤 line counts too.
    private val voice = Regex(
        """^\s*(?:🎤️?\s*)?(?:sesli mesaj|ses kayd[ıi]|ses mesaj[ıi]|voice message|audio)?\s*(?:\((\d{1,2}):(\d{2})\))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Duration in seconds when known, -1 when the line is a voice message without duration, null when it isn't one. */
    fun voiceDuration(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = voice.matchEntire(t) ?: return null
        val hasWord = Regex("""(?i)sesli mesaj|ses kayd|ses mesaj|voice message|audio""").containsMatchIn(t)
        if (!hasWord && !t.contains("🎤")) return null
        val min = m.groupValues[1]
        return if (min.isEmpty()) -1 else min.toInt() * 60 + m.groupValues[2].toInt()
    }

    fun hints(n: NotifData): List<VoiceHint> {
        if (n.pkg !in PACKAGES || n.isSummary) return emptyList()
        return n.messages.mapNotNull { m ->
            if (m.self) return@mapNotNull null
            val d = voiceDuration(m.text) ?: return@mapNotNull null
            val sender = m.sender?.takeIf { it.isNotBlank() } ?: n.title?.takeIf { it.isNotBlank() } ?: "WhatsApp"
            val chat = if (n.isGroup) n.conversation ?: n.title else null
            VoiceHint(n.pkg, sender, chat, m.timeMs, n.postTime, d.takeIf { it >= 0 })
        }
    }
}

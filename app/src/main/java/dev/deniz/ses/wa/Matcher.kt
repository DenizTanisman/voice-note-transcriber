package dev.deniz.ses.wa

import kotlin.math.abs
import kotlin.math.min

/** A voice note file in WhatsApp's folder. */
data class VoiceFile(val path: String, val mtime: Long, val durationMs: Long?)

/**
 * Time-stamp matching: a voice note's file is written by WhatsApp right before it posts the notification,
 * so its mtime sits next to the notification's post time. The message's own time can be much older (phone was
 * offline), so both are tried. The duration shown in the notification ("0:12") breaks ties and rules out
 * neighbours when several notes arrive together.
 */
object Matcher {
    const val WINDOW_MS = 180_000L
    private const val DURATION_SLACK_MS = 1_500L

    fun pick(hint: VoiceHint, candidates: List<VoiceFile>, claimed: Set<String>): VoiceFile? =
        candidates.asSequence()
            .filter { it.path !in claimed }
            .filter { durationOk(hint, it) }
            .map { it to gap(hint, it) }
            .filter { it.second <= WINDOW_MS }
            .minByOrNull { it.second }
            ?.first

    /** Several hints from one notification: oldest message first, each claims one file. */
    fun assign(hints: List<VoiceHint>, candidates: List<VoiceFile>, claimed: Set<String>): Map<VoiceHint, VoiceFile> {
        val taken = claimed.toMutableSet()
        val out = LinkedHashMap<VoiceHint, VoiceFile>()
        // Files in mtime order are handed out in message order: WhatsApp downloads them in arrival order.
        for (h in hints.sortedBy { it.msgTime }) {
            val f = pick(h, candidates.sortedBy { it.mtime }, taken) ?: continue
            out[h] = f
            taken += f.path
        }
        return out
    }

    fun gap(h: VoiceHint, f: VoiceFile): Long = min(abs(f.mtime - h.postTime), abs(f.mtime - h.msgTime))

    private fun durationOk(h: VoiceHint, f: VoiceFile): Boolean {
        val want = h.durationSec ?: return true
        val have = f.durationMs ?: return true
        return abs(have - want * 1000L) <= DURATION_SLACK_MS + 500 // the notification rounds down to whole seconds
    }
}

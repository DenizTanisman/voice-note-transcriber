package dev.deniz.ses.wa

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import java.io.File

/** WhatsApp keeps voice notes as .../WhatsApp Voice Notes/<yyyyww>/PTT-yyyymmdd-WAnnnn.opus. Needs all-files access. */
object WaFolder {

    fun roots(): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        return listOf(
            File(sd, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(sd, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
            File(sd, "WhatsApp/Media/WhatsApp Voice Notes"), // pre-Android 11 layout, if it was ever migrated back
        ).filter { it.isDirectory }
    }

    fun readable(): Boolean = roots().any { it.listFiles() != null }

    /** Newest first, mtime in [since, until]. Walks week folders from the newest down and stops early. */
    fun recent(limit: Int, since: Long = 0, until: Long = Long.MAX_VALUE): List<File> {
        val out = ArrayList<File>()
        for (root in roots()) {
            val weeks = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: continue
            for (w in weeks) {
                val files = w.listFiles { f -> f.isFile && isAudio(f.name) } ?: continue
                out += files
                if (since == 0L && out.size >= limit) break
                if (since > 0 && files.all { it.lastModified() < since }) break
            }
            // Some phones keep notes loose in the root.
            root.listFiles { f -> f.isFile && isAudio(f.name) }?.let { out += it }
        }
        return out.filter { it.lastModified() in since..until }.sortedByDescending { it.lastModified() }.take(limit)
    }

    fun isAudio(name: String) = name.endsWith(".opus", true) || name.endsWith(".ogg", true) ||
        name.endsWith(".m4a", true) || name.endsWith(".aac", true) || name.endsWith(".mp3", true)

    /** Container duration without decoding; null if the file is still being written. */
    fun durationMs(f: File): Long? = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
        } finally { ex.release() }
    } catch (_: Exception) { null }

    fun toVoiceFile(f: File) = VoiceFile(f.absolutePath, f.lastModified(), durationMs(f))
}

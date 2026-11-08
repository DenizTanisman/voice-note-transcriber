package dev.deniz.ses.asr

data class Segment(val startMs: Long, val endMs: Long, val noSpeech: Float, val text: String)

/** Pure helpers around whisper output: parsing and cleaning. Unit tested. */
object Segments {

    fun parse(raw: String): List<Segment> = raw.lineSequence().mapNotNull { line ->
        val p = line.split('\t', limit = 4)
        if (p.size < 4) return@mapNotNull null
        Segment(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, p[2].toFloatOrNull() ?: 0f, p[3].trim())
    }.filter { it.text.isNotEmpty() }.toList()

    /**
     * Turkish Whisper models were trained on subtitle files; on silence or music they "hear" the subtitle
     * credits. Those lines are dropped when they are a whole segment.
     */
    private val hallucinations = listOf(
        Regex("""^altyaz[ıi]\s*m\.?\s*k\.?$""", RegexOption.IGNORE_CASE),
        Regex("""^altyaz[ıi]\s*(:|.{0,30}(tarafından|hazırla|çeviri|editör|by)).{0,40}$""", RegexOption.IGNORE_CASE),
        Regex("""^(abone ol(mayı unutmayın)?|izlediğiniz için teşekkürler|bir sonraki videoda görüşmek üzere)[.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^(thank you|thanks for watching|subtitles by.*)[.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^[\[(*♪].*[\])*♪]$"""), // [Müzik], (gülüşmeler), ♪ ... ♪
    )

    fun isHallucination(s: Segment): Boolean {
        val t = s.text.trim().trim('.', ' ')
        return t.isEmpty() || hallucinations.any { it.matches(s.text.trim()) || it.matches(t) }
    }

    /** Drops credit lines and immediate repeats (a looping decoder), joins the rest into one paragraph. */
    fun clean(segs: List<Segment>): String {
        val kept = ArrayList<String>()
        for (s in segs) {
            if (isHallucination(s)) continue
            val t = collapseRepeats(s.text.trim())
            if (kept.isNotEmpty() && norm(kept.last()) == norm(t)) continue
            kept += t
        }
        return kept.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
    }

    /** "evet evet evet evet evet evet" style loops: a word or short phrase repeated >3 times in a row → kept twice. */
    fun collapseRepeats(t: String): String =
        Regex("""(\b[\p{L}\p{N}'’]+(?:\s+[\p{L}\p{N}'’]+){0,3}\b[,.]?)(?:\s+\1){3,}""", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]} ${m.groupValues[1]}" }

    private fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
}

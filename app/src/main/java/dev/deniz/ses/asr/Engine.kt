package dev.deniz.ses.asr

import android.content.Context
import android.os.Environment
import android.util.Log
import dev.deniz.ses.data.Prefs
import java.io.File

data class Result(val text: String, val segments: List<Segment>, val model: String, val tookMs: Long)

enum class Pass(val beam: Int) {
    /** Text within seconds: small model, greedy. ~24–27 % WER on real voice notes. */
    FAST(1),
    /** Replaces it: medium/turbo with beam search. ~12–14 % WER, 4–6× slower. */
    GOOD(5),
}

/**
 * Owns the whisper context. One model in memory at a time; freed after a minute idle.
 * All calls come from the single worker thread in [dev.deniz.ses.service.Jobs].
 */
class Engine(private val ctx: Context, private val prefs: Prefs) {
    private var handle = 0L
    private var loaded: File? = null

    /** Prompt in the target language nudges Whisper towards punctuation and Turkish spelling. */
    private val prompts = mapOf("tr" to "Merhaba, nasılsın? Ben de iyiyim, teşekkür ederim. Akşam görüşürüz.")

    fun modelDirs(): List<File> = listOfNotNull(
        ctx.getExternalFilesDir("models"),
        File(Environment.getExternalStorageDirectory(), "Download/Modeller"),
    )

    fun models(): List<File> = modelDirs().flatMap { d ->
        d.listFiles { f -> f.isFile && f.name.startsWith("ggml-") && f.name.endsWith(".bin") && !f.name.contains("silero") }
            ?.toList().orEmpty()
    }.distinctBy { it.name }

    private fun rank(f: File, order: List<String>) = order.indexOfFirst { f.name.contains(it) }.let { if (it < 0) 99 else it }

    /** Fast pass: the chosen file, else the smallest usable one. */
    fun fastModel(): File? = models().let { all ->
        all.firstOrNull { it.name == prefs.fastModel } ?: all.minByOrNull { rank(it, listOf("small", "base", "medium", "tiny", "turbo", "large")) }
    }

    /** Accurate pass: the chosen file, else medium (turbo is more accurate but ~2× slower on this CPU). */
    fun goodModel(): File? = models().let { all ->
        all.firstOrNull { it.name == prefs.goodModel } ?: all.minByOrNull { rank(it, listOf("medium", "large-v3-turbo", "large", "small")) }
    }

    fun model(pass: Pass): File? = if (pass == Pass.FAST) fastModel() else goodModel()

    fun transcribe(pcm16k: FloatArray, file: File, beam: Int): Result {
        val t0 = System.currentTimeMillis()
        ensure(file)
        val lang = prefs.lang
        // Whisper wants ≥1 s; pad short clips with silence.
        val pcm = if (pcm16k.size < 16_000) pcm16k.copyOf(16_000) else pcm16k
        val raw = WhisperNative.transcribe(handle, pcm, lang, prefs.threads, prompts[lang], beam, 0)
            ?: error("Yazıya dökülemedi")
        val segs = Segments.parse(String(raw, Charsets.UTF_8))
        val took = System.currentTimeMillis() - t0
        Log.i(TAG, "${file.name} b$beam ${pcm.size / 16}ms audio → ${took}ms, ${segs.size} seg")
        return Result(Segments.clean(segs), segs, file.name, took)
    }

    fun transcribe(pcm16k: FloatArray, pass: Pass): Result {
        val file = model(pass) ?: error("Model yok. ${modelDirs().first().absolutePath} klasörüne ggml-*.bin koyun.")
        return transcribe(pcm16k, file, pass.beam)
    }

    @Synchronized private fun ensure(file: File) {
        if (handle != 0L && loaded == file) return
        release()
        val h = WhisperNative.init(file.absolutePath)
        if (h == 0L) error("Model açılamadı: ${file.name}")
        handle = h
        loaded = file
    }

    @Synchronized fun release() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
        loaded = null
    }

    companion object { const val TAG = "SesEngine" }
}

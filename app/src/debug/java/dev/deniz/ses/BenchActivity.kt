package dev.deniz.ses

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import dev.deniz.ses.audio.Decoder
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Debug only: same files through each engine/setting, results as JSON lines in files/bench/results.jsonl. */
class BenchActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log = TextView(this).apply { setPadding(24, 48, 24, 24); textSize = 12f }
        setContentView(ScrollView(this).apply { addView(log) })
        val files = intent.getStringExtra("files").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val runs = intent.getStringExtra("runs").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = File(getExternalFilesDir("bench"), "results.jsonl")
        thread(name = "bench") { bench(files, runs, out) }
    }

    private fun say(s: String) { Log.i("SesBench", s); runOnUiThread { log.append(s + "\n") } }

    private fun bench(files: List<String>, runs: List<String>, out: File) {
        val p = App.prefs
        val saved = p.threads
        val audio = files.associateWith { f -> Decoder.decode(File(f)) }
        say("decoded ${audio.size} files")
        val engine = App.jobs.engine
        for (run in runs) {
            val parts = run.split(':')
            try {
                run {
                    val model = engine.models().first { it.name == parts[1] }
                    p.threads = parts.getOrNull(2)?.toInt() ?: 6
                    val beam = parts.getOrNull(3)?.toInt() ?: 1
                    engine.release()
                    val l0 = System.currentTimeMillis()
                    engine.transcribe(FloatArray(16_000), model, 1) // load + warm up
                    val load = System.currentTimeMillis() - l0
                    for ((f, a) in audio) {
                        val r = engine.transcribe(a.pcm16k, model, beam)
                        write(out, run, f, a.durationMs, r.tookMs, r.text, load)
                    }
                    engine.release()
                }
            } catch (t: Throwable) {
                say("$run FAILED: $t"); Log.e("SesBench", run, t)
            }
        }
        p.threads = saved
        say("DONE")
    }

    private fun write(out: File, run: String, f: String, audioMs: Long, took: Long, text: String, load: Long) {
        val j = JSONObject().put("run", run).put("file", File(f).name).put("audio_ms", audioMs).put("took_ms", took)
            .put("load_ms", load).put("text", text)
        out.appendText(j.toString() + "\n")
        say("$run ${File(f).name} ${audioMs}ms → ${took}ms")
    }
}

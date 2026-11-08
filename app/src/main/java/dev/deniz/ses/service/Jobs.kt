package dev.deniz.ses.service

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import dev.deniz.ses.asr.Engine
import dev.deniz.ses.asr.Pass
import dev.deniz.ses.audio.Decoder
import dev.deniz.ses.data.Db
import dev.deniz.ses.data.Prefs
import dev.deniz.ses.data.Source
import dev.deniz.ses.data.State
import dev.deniz.ses.data.Voice
import dev.deniz.ses.wa.Matcher
import dev.deniz.ses.wa.VoiceHint
import dev.deniz.ses.wa.WaFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Everything that touches Whisper goes through one thread, in order. Waiting for WhatsApp to finish writing a
 * file happens on IO so a slow download never holds up the queue.
 */
class Jobs(private val ctx: Context, private val db: Db, private val prefs: Prefs, val engine: Engine) {
    private val whisper = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private var idle: Job? = null
    /** Jobs on or waiting for the whisper thread; the foreground service lives while > 0. Whisper thread only. */
    private var active = 0

    private val _changes = MutableStateFlow(0L)
    /** Bumps whenever a row changes; the UI re-reads on it. */
    val changes: StateFlow<Long> = _changes
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running

    /** Tap target of the original WhatsApp notification, so our copy opens the same chat. */
    private val chatIntents = ConcurrentHashMap<String, PendingIntent>()

    private fun bump() { _changes.value = System.nanoTime() }

    // ---- WhatsApp notifications -------------------------------------------------------------------------

    fun onHints(hints: List<VoiceHint>, open: PendingIntent?) {
        if (!prefs.auto) return
        val fresh = hints.filter { db.byHint(it.key) == null && inFlight.add(it.key) }
        if (fresh.isEmpty()) return
        fresh.forEach { h -> open?.let { chatIntents[h.key] = it } }
        scope.launch {
            try { matchAndRun(fresh) } finally { fresh.forEach { inFlight.remove(it.key) } }
        }
    }

    private suspend fun matchAndRun(hints: List<VoiceHint>) {
        val since = hints.minOf { minOf(it.msgTime, it.postTime) } - Matcher.WINDOW_MS
        val until = hints.maxOf { maxOf(it.msgTime, it.postTime) } + Matcher.WINDOW_MS
        var pending = hints
        // WhatsApp downloads voice notes itself; give it up to a minute on a slow connection.
        repeat(30) { attempt ->
            val claimed = db.claimedSince(since - Matcher.WINDOW_MS)
            val files = WaFolder.recent(50, since, until).filter { stable(it) }.map { WaFolder.toVoiceFile(it) }
            val got = Matcher.assign(pending, files, claimed)
            for ((h, f) in got) {
                val existing = db.get(f.path)
                val row = (existing ?: Voice(id = f.path, fileTime = f.mtime, durationMs = f.durationMs ?: 0)).copy(
                    sender = h.sender, chat = h.chat, hintKey = h.key, msgTime = h.msgTime, source = Source.AUTO,
                )
                if (existing?.state == State.DONE && existing.pass == 2) {
                    db.put(row); bump()
                    Notifier.result(ctx, row, chatIntents.remove(h.key))
                } else {
                    db.put(row.copy(state = State.QUEUED)); bump()
                    runFile(f.path, notify = true)
                }
            }
            pending = pending - got.keys
            if (pending.isEmpty()) return
            if (attempt == 0) Log.i(TAG, "waiting for ${pending.size} voice file(s); hints=${pending.map { "${it.postTime}/${it.durationSec}" }} " +
                "candidates=${files.map { "${File(it.path).name}@${it.mtime}/${it.durationMs}" }}")
            delay(2_000)
        }
        Log.w(TAG, "no file for ${pending.map { it.key }}")
    }

    /** A file still being written grows between two looks. */
    private suspend fun stable(f: File): Boolean {
        if (System.currentTimeMillis() - f.lastModified() > 3_000) return true
        val a = f.length(); delay(700); return a > 0 && a == f.length()
    }

    // ---- Manual and shared ------------------------------------------------------------------------------

    fun enqueueFile(path: String) {
        val f = File(path)
        val existing = db.get(path)
        db.put((existing ?: Voice(id = path, fileTime = f.lastModified(), source = Source.MANUAL)).copy(state = State.QUEUED, error = ""))
        bump()
        scope.launch { runFile(path, notify = false) }
    }

    /** Shared from WhatsApp's share sheet (or any app). [onUpdate] sees the fast text, then the accurate one. */
    suspend fun transcribeUri(uri: Uri, onUpdate: (Voice) -> Unit): Voice = withContext(whisper) {
        val id = "share:${System.currentTimeMillis()}"
        val row = Voice(id = id, source = Source.SHARE, state = State.RUNNING, fileTime = System.currentTimeMillis())
        work(row, notify = false, onUpdate = onUpdate) { Decoder.decode(ctx, uri) }
    }

    fun cancel() = dev.deniz.ses.asr.WhisperNative.cancel()

    private suspend fun runFile(path: String, notify: Boolean) = withContext(whisper) {
        val row = db.get(path) ?: return@withContext
        work(row.copy(state = State.RUNNING), notify) { Decoder.decode(File(path)) }
    }

    /**
     * Runs on the whisper thread: fast pass → row + notification, then the accurate pass replaces both.
     * The accurate pass is skipped when it would use the same model or none is installed.
     */
    private fun work(start: Voice, notify: Boolean, onUpdate: (Voice) -> Unit = {}, decode: () -> Decoder.Audio): Voice {
        idle?.cancel()
        if (active++ == 0) WorkService.start(ctx)
        db.put(start); _running.value = start.id; bump()
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ses:whisper").apply { acquire(10 * 60_000L) }
        val open = start.hintKey?.let { chatIntents.remove(it) }
        var row = start
        try {
            val audio = decode()
            val fast = engine.fastModel()
            val good = engine.goodModel().takeIf { prefs.refine && it != null && it != fast }
            val first = engine.transcribe(audio.pcm16k, fast ?: good ?: error("Model yok"), if (fast != null) Pass.FAST.beam else Pass.GOOD.beam)
            row = start.done(first, audio, pass = if (good == null || fast == null) 2 else 1)
            db.put(row); bump(); onUpdate(row)
            if (notify) Notifier.result(ctx, row, open, refining = row.pass == 1)
            if (row.pass == 1 && good != null && row.state == State.DONE) {
                val second = try { engine.transcribe(audio.pcm16k, good, Pass.GOOD.beam) } catch (e: Throwable) {
                    Log.w(TAG, "accurate pass failed", e); null
                }
                row = if (second != null && second.text.isNotBlank()) row.done(second, audio, pass = 2).copy(tookMs = row.tookMs + second.tookMs)
                else row.copy(pass = 2)
                db.put(row); bump(); onUpdate(row)
                if (notify) Notifier.result(ctx, row, open)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed ${start.id}", e)
            if (row.state != State.DONE) {
                row = start.copy(state = State.FAILED, error = e.message ?: e.javaClass.simpleName, updated = System.currentTimeMillis())
                db.put(row); bump(); onUpdate(row)
            }
        } finally {
            wl.release()
            _running.value = null
            if (--active == 0) WorkService.stop(ctx)
        }
        idle = scope.launch { delay(60_000); withContext(whisper) { engine.release() } }
        return row
    }

    private fun Voice.done(r: dev.deniz.ses.asr.Result, audio: Decoder.Audio, pass: Int) = copy(
        text = r.text, state = if (r.text.isBlank()) State.EMPTY else State.DONE, error = "", model = r.model,
        tookMs = r.tookMs, durationMs = audio.durationMs, pass = pass, updated = System.currentTimeMillis(),
    )

    companion object { const val TAG = "SesJobs" }
}

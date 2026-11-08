package dev.deniz.ses.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class State { QUEUED, RUNNING, DONE, EMPTY, FAILED }
enum class Source { AUTO, MANUAL, SHARE }

data class Voice(
    /** File path for WhatsApp notes, "share:<time>" for shared audio. */
    val id: String,
    val sender: String? = null,
    val chat: String? = null,
    val hintKey: String? = null,
    val msgTime: Long = 0,
    val fileTime: Long = 0,
    val durationMs: Long = 0,
    val text: String = "",
    val state: State = State.QUEUED,
    val error: String = "",
    val model: String = "",
    val tookMs: Long = 0,
    val source: Source = Source.MANUAL,
    /** 1 = fast text, 2 = accurate text (or the fast one was all there is). */
    val pass: Int = 0,
    val updated: Long = System.currentTimeMillis(),
) {
    val label: String? get() = when {
        sender == null -> null
        chat != null && chat != sender -> "$sender · $chat"
        else -> sender
    }
}

/** Plain SQLite in app-private storage. Transcripts never leave the phone. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "ses.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE voice(
                id TEXT PRIMARY KEY, sender TEXT, chat TEXT, hint_key TEXT, msg_time INTEGER NOT NULL DEFAULT 0,
                file_time INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '',
                took_ms INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL, pass INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX voice_hint ON voice(hint_key)")
        db.execSQL("CREATE INDEX voice_time ON voice(file_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only ever held test runs.
        db.execSQL("DROP TABLE IF EXISTS voice")
        onCreate(db)
    }

    @Synchronized fun put(v: Voice) {
        writableDatabase.insertWithOnConflict("voice", null, v.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun get(id: String): Voice? =
        readableDatabase.query("voice", null, "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.voice() else null }

    @Synchronized fun byHint(key: String): Voice? =
        readableDatabase.query("voice", null, "hint_key=?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.voice() else null }

    @Synchronized fun byIds(ids: Collection<String>): Map<String, Voice> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Voice>()
        ids.chunked(500).forEach { part ->
            val q = part.joinToString(",") { "?" }
            readableDatabase.query("voice", null, "id IN ($q)", part.toTypedArray(), null, null, null).use {
                while (it.moveToNext()) it.voice().let { v -> out[v.id] = v }
            }
        }
        return out
    }

    /** Paths already tied to a sender: the matcher must not hand them out twice. */
    @Synchronized fun claimedSince(t: Long): Set<String> =
        readableDatabase.rawQuery("SELECT id FROM voice WHERE hint_key IS NOT NULL AND file_time >= ?", arrayOf(t.toString())).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }

    @Synchronized fun shares(limit: Int): List<Voice> =
        readableDatabase.query("voice", null, "source=?", arrayOf(Source.SHARE.name), null, null, "updated DESC", limit.toString())
            .use { c -> buildList { while (c.moveToNext()) add(c.voice()) } }

    /** A run killed with the process stays RUNNING forever otherwise. */
    @Synchronized fun resetStale() {
        writableDatabase.execSQL("UPDATE voice SET state='FAILED', error='Yarıda kaldı' WHERE state IN ('RUNNING','QUEUED')")
    }

    private fun Voice.toValues() = ContentValues().apply {
        put("id", id); put("sender", sender); put("chat", chat); put("hint_key", hintKey)
        put("msg_time", msgTime); put("file_time", fileTime); put("duration_ms", durationMs); put("text", text)
        put("state", state.name); put("error", error); put("model", model); put("took_ms", tookMs)
        put("source", source.name); put("pass", pass); put("updated", updated)
    }

    private fun Cursor.voice(): Voice {
        fun s(c: String) = getString(getColumnIndexOrThrow(c))
        fun l(c: String) = getLong(getColumnIndexOrThrow(c))
        return Voice(
            id = s("id"), sender = s("sender"), chat = s("chat"), hintKey = s("hint_key"), msgTime = l("msg_time"),
            fileTime = l("file_time"), durationMs = l("duration_ms"), text = s("text"), state = State.valueOf(s("state")),
            error = s("error"), model = s("model"), tookMs = l("took_ms"), source = Source.valueOf(s("source")),
            pass = getInt(getColumnIndexOrThrow("pass")), updated = l("updated"),
        )
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }
    }
}
